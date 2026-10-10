// Low-latency forwarding engine on top of Oboe / AAudio.
//
// Design constraints:
// - The audio callback performs no allocation, no locking, no JNI and no disk I/O.
// - Each physical device gets exactly one multi-channel stream; routes share it.
// - Routes carry mono samples through single-producer single-consumer lock-free
//   rings; a starving route primes itself again and adapts its cushion.
// - Input and output rates must match after the system negotiates both streams;
//   this engine never performs application-level sample-rate conversion.

#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <thread>
#include <vector>

namespace {

constexpr uint32_t kRingCapacity = 16384;
constexpr uint32_t kSpectrumHistory = 1024;
constexpr int kMaxChannels = 32;
constexpr int kMaxRoutes = 128;
constexpr int kSpectrumFft = 512;

// Single-producer single-consumer wait-free ring of mono float samples.
struct Ring {
    std::array<float, kRingCapacity> data{};
    std::atomic<uint32_t> write{0};
    std::atomic<uint32_t> read{0};

    bool push(float value) {
        const auto w = write.load(std::memory_order_relaxed);
        if (w - read.load(std::memory_order_acquire) >= kRingCapacity) return false;
        data[w % kRingCapacity] = value;
        write.store(w + 1, std::memory_order_release);
        return true;
    }

    bool pop(float &value) {
        const auto r = read.load(std::memory_order_relaxed);
        if (r == write.load(std::memory_order_acquire)) return false;
        value = data[r % kRingCapacity];
        read.store(r + 1, std::memory_order_release);
        return true;
    }

    uint32_t available() const {
        return write.load(std::memory_order_acquire) - read.load(std::memory_order_relaxed);
    }

    // Drop the oldest samples so a scheduling stall cannot become permanent delay.
    void trim(uint32_t keep) {
        const auto w = write.load(std::memory_order_acquire);
        const auto r = read.load(std::memory_order_relaxed);
        if (w - r > keep) read.store(w - keep, std::memory_order_release);
    }
};

struct Engine;

// One opened stream per physical device endpoint.
struct Endpoint : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
    Engine *engine;
    int deviceId;
    int channels;
    bool input;
    int requestedRate = 0;   // 0 = let the system choose
    int requestedFormat = 0; // 0 = let the system choose
    int exclusive = 0;
    int inputPreset = 0;
    std::vector<float> scratch;
    Ring testRing; // ringtone playback only
    std::atomic<int> testChannel{0};
    int64_t testDrainFrame = -1; // controlMutex only; snapshot after the last test write
    std::shared_ptr<oboe::AudioStream> stream;
    std::vector<std::unique_ptr<std::array<std::atomic<float>, kSpectrumHistory>>> history;
    std::atomic<uint32_t> historyWrite{0};
    std::atomic<uint64_t> frames{0};
    std::atomic<int32_t> xruns{0};
    std::atomic<uint64_t> gaps{0};
    std::unique_ptr<oboe::LatencyTuner> tuner;
    uint64_t lastFrames = 0;
    std::chrono::steady_clock::time_point lastProgress = std::chrono::steady_clock::now();

    Endpoint(Engine *e, int device, int channelCount, bool isInput)
        : engine(e), deviceId(device), channels(channelCount), input(isInput) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    bool onError(oboe::AudioStream *stream, oboe::Result error) override;
};

// One forwarding pair: input channel -> output channel through a private ring.
struct Route {
    Endpoint *in;
    Endpoint *out;
    int inChannel;
    int outChannel;
    Ring ring;
    bool primed = false;
    std::atomic<int> cushion{1};
    std::atomic<uint64_t> underflows{0};

    Route(Endpoint *i, Endpoint *o, int ic, int oc) : in(i), out(o), inChannel(ic), outChannel(oc) {}

    bool sample(float &value) {
        const int target = std::max(2, in->stream->getFramesPerBurst() *
                                          cushion.load(std::memory_order_relaxed) +
                                      out->stream->getFramesPerBurst());
        if (!primed) {
            if (ring.available() < static_cast<uint32_t>(target)) return false;
            primed = true;
        }
        if (!ring.pop(value)) {
            primed = false;
            underflows.fetch_add(1, std::memory_order_relaxed);
            cushion.store(std::min(8, cushion.load(std::memory_order_relaxed) + 1),
                          std::memory_order_relaxed);
            return false;
        }
        return true;
    }
};

struct Engine {
    std::vector<std::unique_ptr<Endpoint>> endpoints;
    std::vector<std::unique_ptr<Route>> routes;
    std::atomic<int> error{0};
    std::atomic<bool> active{false};

    void fail(oboe::Result result) {
        int expected = 0;
        error.compare_exchange_strong(expected, static_cast<int>(result));
        active.store(false);
    }

    Endpoint *endpoint(int deviceId, int channels, bool input) {
        for (auto &e : endpoints) {
            if (e->deviceId == deviceId && e->input == input) {
                e->channels = std::max(e->channels, channels);
                return e.get();
            }
        }
        endpoints.push_back(std::make_unique<Endpoint>(this, deviceId, channels, input));
        return endpoints.back().get();
    }

    // Registers an endpoint from a route spec; rejects conflicting configurations
    // when the same device appears in multiple routes with different parameters.
    Endpoint *configured(const int *spec, bool input) {
        if (spec[0] == -1) return nullptr;
        for (auto &e : endpoints) {
            if (e->deviceId == spec[0] && e->input == input &&
                (e->requestedRate != spec[3] || e->requestedFormat != spec[4] ||
                 e->exclusive != spec[5] || e->inputPreset != spec[6])) {
                throw std::invalid_argument("AAudio same-device configurations differ");
            }
        }
        Endpoint *e = endpoint(spec[0], spec[2], input);
        e->requestedRate = spec[3];
        e->requestedFormat = spec[4];
        e->exclusive = spec[5];
        e->inputPreset = spec[6];
        return e;
    }

    void stop() {
        active.store(false);
        // Streams must be quiescent before callbacks and rings are destroyed.
        for (auto &e : endpoints) {
            if (e->stream) e->stream->stop(1000000000);
        }
        for (auto &e : endpoints) {
            if (e->stream) {
                e->stream->close();
                e->stream.reset();
            }
        }
    }

    ~Engine() { stop(); }

    std::string open(Endpoint &e) {
        oboe::AudioStreamBuilder builder;
        builder.setAudioApi(oboe::AudioApi::AAudio)
            ->setDirection(e.input ? oboe::Direction::Input : oboe::Direction::Output)
            ->setDeviceId(e.deviceId)
            ->setChannelCount(e.channels)
            ->setFormat(static_cast<oboe::AudioFormat>(e.requestedFormat))
            ->setSampleRate(e.requestedRate)
            ->setFormatConversionAllowed(false)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(e.exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setDataCallback(&e)
            ->setErrorCallback(&e)
            ->setUsage(oboe::Usage::Game)
            ->setInputPreset(static_cast<oboe::InputPreset>(e.inputPreset));
        oboe::Result result = builder.openStream(e.stream);
        if (result != oboe::Result::OK) return oboe::convertToText(result);
        if (e.stream->getDeviceId() != e.deviceId) {
            std::ostringstream message;
            message << "AAudio routing: requested deviceId=" << e.deviceId
                    << ", returned deviceId=" << e.stream->getDeviceId();
            return message.str();
        }
        if (e.stream->getChannelCount() != e.channels) {
            return "AAudio returned channelCount=" + std::to_string(e.stream->getChannelCount()) +
                   ", requested=" + std::to_string(e.channels);
        }
        const oboe::AudioFormat actual = e.stream->getFormat();
        if (actual != oboe::AudioFormat::Float && actual != oboe::AudioFormat::I16 &&
            actual != oboe::AudioFormat::I24 && actual != oboe::AudioFormat::I32) {
            return oboe::convertToText(oboe::Result::ErrorInvalidFormat);
        }
        if (e.requestedRate && e.stream->getSampleRate() != e.requestedRate) {
            return "AAudio requested sampleRate=" + std::to_string(e.requestedRate) +
                   ", actual=" + std::to_string(e.stream->getSampleRate());
        }
        if (e.requestedFormat && static_cast<int>(actual) != e.requestedFormat) {
            return "AAudio requested format=" + std::to_string(e.requestedFormat) +
                   ", actual=" + oboe::convertToText(actual);
        }
        if (e.exclusive && e.stream->getSharingMode() != oboe::SharingMode::Exclusive) {
            return std::string("AAudio requested sharingMode=Exclusive, actual=") +
                   oboe::convertToText(e.stream->getSharingMode());
        }
        e.scratch.resize(kRingCapacity * e.channels);
        if (!e.input) {
            auto size = e.stream->setBufferSizeInFrames(e.stream->getFramesPerBurst() * 2);
            if (!size) return oboe::convertToText(size.error());
            e.tuner = std::make_unique<oboe::LatencyTuner>(*e.stream);
        }
        if (e.input) {
            e.history.clear();
            e.historyWrite.store(0);
            for (int c = 0; c < e.channels; ++c) {
                auto buffer = std::make_unique<std::array<std::atomic<float>, kSpectrumHistory>>();
                for (auto &v : *buffer) v.store(0);
                e.history.push_back(std::move(buffer));
            }
        }
        return "";
    }
};

float readPcm(const void *data, int index, oboe::AudioFormat format) {
    if (format == oboe::AudioFormat::Float) return static_cast<const float *>(data)[index];
    if (format == oboe::AudioFormat::I16) return static_cast<const int16_t *>(data)[index] / 32768.f;
    if (format == oboe::AudioFormat::I32) return static_cast<const int32_t *>(data)[index] / 2147483648.f;
    const auto *p = static_cast<const uint8_t *>(data) + index * 3;
    int32_t value = p[0] | (p[1] << 8) | (p[2] << 16);
    if (value & 0x800000) value |= static_cast<int32_t>(0xff000000);
    return value / 8388608.f;
}

void writePcm(void *data, const float *values, int count, oboe::AudioFormat format) {
    for (int k = 0; k < count; ++k) {
        const double value = std::clamp(static_cast<double>(values[k]), -1.0, 1.0);
        if (format == oboe::AudioFormat::Float) {
            static_cast<float *>(data)[k] = static_cast<float>(value);
        } else if (format == oboe::AudioFormat::I16) {
            static_cast<int16_t *>(data)[k] =
                static_cast<int16_t>(std::clamp(value * 32768.0, -32768.0, 32767.0));
        } else if (format == oboe::AudioFormat::I32) {
            static_cast<int32_t *>(data)[k] =
                static_cast<int32_t>(std::clamp(value * 2147483648.0, -2147483648.0, 2147483647.0));
        } else {
            const int32_t v = static_cast<int32_t>(std::clamp(value * 8388608.0, -8388608.0, 8388607.0));
            auto *p = static_cast<uint8_t *>(data) + k * 3;
            p[0] = static_cast<uint8_t>(v);
            p[1] = static_cast<uint8_t>(v >> 8);
            p[2] = static_cast<uint8_t>(v >> 16);
        }
    }
}

bool Endpoint::onError(oboe::AudioStream *, oboe::Result result) {
    // Handled from the control thread; do not close, lock or allocate here.
    engine->fail(result);
    return true;
}

oboe::DataCallbackResult Endpoint::onAudioReady(oboe::AudioStream *s, void *buffer, int32_t numFrames) {
    float *samples = scratch.data();
    if (numFrames < 0 || static_cast<uint32_t>(numFrames) > kRingCapacity) {
        engine->fail(oboe::Result::ErrorOutOfRange);
        return oboe::DataCallbackResult::Stop;
    }
    if (!engine->active.load(std::memory_order_acquire)) {
        if (!input) std::memset(buffer, 0, static_cast<size_t>(numFrames) * channels * s->getBytesPerSample());
        return oboe::DataCallbackResult::Stop;
    }
    if (s->getDeviceId() != deviceId) {
        engine->fail(oboe::Result::ErrorDisconnected);
        return oboe::DataCallbackResult::Stop;
    }
    if (input) {
        for (int k = 0; k < numFrames * channels; ++k) {
            samples[k] = readPcm(buffer, k, s->getFormat());
            if (!std::isfinite(samples[k])) {
                engine->fail(oboe::Result::ErrorInvalidFormat);
                return oboe::DataCallbackResult::Stop;
            }
        }
        const auto position = historyWrite.load(std::memory_order_relaxed);
        for (int f = 0; f < numFrames; ++f) {
            for (int c = 0; c < channels; ++c) {
                (*history[c])[(position + f) % kSpectrumHistory].store(
                    samples[f * channels + c], std::memory_order_relaxed);
            }
        }
        historyWrite.store(position + numFrames, std::memory_order_release);
        for (auto &r : engine->routes) {
            if (r->in != this) continue;
            for (int f = 0; f < numFrames; ++f) {
                if (!r->ring.push(samples[f * channels + r->inChannel])) {
                    engine->fail(oboe::Result::ErrorOutOfRange);
                    return oboe::DataCallbackResult::Stop;
                }
            }
        }
    } else {
        std::fill(samples, samples + numFrames * channels, 0.f);
        for (int f = 0; f < numFrames; ++f) {
            float value = 0;
            if (testRing.pop(value)) {
                samples[f * channels + testChannel.load(std::memory_order_relaxed)] = value;
            }
        }
        for (auto &r : engine->routes) {
            if (r->out != this) continue;
            const auto burst = std::max(2, r->in->stream->getFramesPerBurst());
            const auto needed = static_cast<uint32_t>(numFrames + s->getFramesPerBurst()) +
                                burst * (r->cushion.load(std::memory_order_relaxed) + 1);
            r->ring.trim(needed);
            for (int f = 0; f < numFrames; ++f) {
                float value = 0;
                if (!r->sample(value)) gaps.fetch_add(1, std::memory_order_relaxed);
                samples[f * channels + r->outChannel] += value;
            }
        }
        writePcm(buffer, samples, numFrames * channels, s->getFormat());
    }
    frames.fetch_add(numFrames, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

std::mutex controlMutex;
std::unique_ptr<Engine> engine;

jstring toJString(JNIEnv *env, const std::string &text) { return env->NewStringUTF(text.c_str()); }

// Returns the raw error, if any, and tears the engine down before returning it.
std::string stopError() {
    if (!engine) return "";
    const auto code = engine->error.load();
    if (!code) return "";
    const std::string text = oboe::convertToText(static_cast<oboe::Result>(code));
    engine->stop();
    engine.reset();
    return text + " (" + std::to_string(code) + ")";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_startConfigured(JNIEnv *env, jclass, jintArray spec) {
    std::lock_guard<std::mutex> lock(controlMutex);
    try {
        engine.reset();
        if (!spec) return toJString(env, "ErrorIllegalArgument");
        const int length = env->GetArrayLength(spec);
        if (length == 0 || length % 14 || length > 14 * kMaxRoutes) {
            return toJString(env, oboe::convertToText(oboe::Result::ErrorIllegalArgument));
        }
        std::vector<jint> a(length);
        env->GetIntArrayRegion(spec, 0, length, a.data());
        engine = std::make_unique<Engine>();
        for (int k = 0; k < length; k += 14) {
            for (int side : {0, 7}) {
                const int x = k + side;
                if (a[x] != -1 && (a[x] <= 0 || a[x + 1] < 0 || a[x + 2] < 1 || a[x + 2] > kMaxChannels ||
                                   a[x + 1] >= a[x + 2] || a[x + 3] < 0 || a[x + 3] > 384000 ||
                                   a[x + 4] < 0 || a[x + 4] > 4 || a[x + 5] < 0 || a[x + 5] > 1)) {
                    engine.reset();
                    return toJString(env, oboe::convertToText(oboe::Result::ErrorIllegalArgument));
                }
            }
            Endpoint *in = engine->configured(&a[k], true);
            Endpoint *out = engine->configured(&a[k + 7], false);
            if (in && out) {
                engine->routes.push_back(std::make_unique<Route>(in, out, a[k + 1], a[k + 8]));
            }
            if (!in && !out) {
                engine.reset();
                return toJString(env, "ErrorIllegalArgument");
            }
        }
        for (bool input : {false, true}) {
            for (auto &e : engine->endpoints) {
                if (e->input != input) continue;
                const std::string error = engine->open(*e);
                if (!error.empty()) {
                    engine.reset();
                    return toJString(env, error);
                }
            }
        }
        for (auto &route : engine->routes) {
            if (route->in->stream->getSampleRate() != route->out->stream->getSampleRate()) {
                const std::string message =
                    "ErrorInvalidRate: AAudio input sampleRate=" +
                    std::to_string(route->in->stream->getSampleRate()) +
                    ", output sampleRate=" + std::to_string(route->out->stream->getSampleRate()) +
                    "; no system cross-stream sample-rate converter";
                engine.reset();
                return toJString(env, message);
            }
        }
        engine->active.store(true);
        for (bool input : {true, false}) {
            for (auto &e : engine->endpoints) {
                if (e->input != input) continue;
                const oboe::Result result = e->stream->requestStart();
                if (result != oboe::Result::OK) {
                    engine.reset();
                    return toJString(env, oboe::convertToText(result));
                }
            }
        }
        return toJString(env, stopError());
    } catch (const std::exception &e) {
        engine.reset();
        return toJString(env, e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_stop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    engine.reset();
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_pollError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine && !engine->error.load() && engine->active.load()) {
        const auto now = std::chrono::steady_clock::now();
        for (auto &e : engine->endpoints) {
            const auto frames = e->frames.load();
            if (frames != e->lastFrames) {
                e->lastFrames = frames;
                e->lastProgress = now;
            } else if (now - e->lastProgress > std::chrono::seconds(2)) {
                engine->fail(oboe::Result::ErrorTimeout);
                break;
            }
            if (e->tuner) {
                auto r = e->tuner->tune();
                if (r != oboe::Result::OK && r != oboe::Result::ErrorUnimplemented) {
                    engine->fail(r);
                    break;
                }
            }
            auto count = e->stream->getXRunCount();
            if (count) e->xruns.store(count.value());
        }
    }
    return toJString(env, stopError());
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_endpointStats(JNIEnv *env, jclass, jint deviceId, jboolean input) {
    std::lock_guard<std::mutex> lock(controlMutex);
    std::ostringstream text;
    if (engine) {
        for (auto &e : engine->endpoints) {
            if (e->deviceId == deviceId && e->input == static_cast<bool>(input) && e->stream) {
                auto *s = e->stream.get();
                text << s->getSampleRate() << " Hz · " << oboe::convertToText(s->getFormat()) << " · "
                     << s->getChannelCount() << " ch · " << oboe::convertToText(s->getSharingMode()) << " / "
                     << oboe::convertToText(s->getPerformanceMode()) << "\nburst=" << s->getFramesPerBurst()
                     << " · buffer=" << s->getBufferSizeInFrames() << " · frames=" << e->frames.load()
                     << " · xrun=" << e->xruns.load();
                if (s->getHardwareSampleRate() > 0) {
                    text << "\nhardware: " << s->getHardwareSampleRate() << " Hz · "
                         << oboe::convertToText(s->getHardwareFormat()) << " · "
                         << s->getHardwareChannelCount() << " ch";
                }
            }
        }
    }
    return toJString(env, text.str());
}

// Opens an exclusive-mode stream just to probe whether the device grants it.
extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_exclusiveSupport(JNIEnv *, jclass, jint deviceId, jboolean input, jint channels) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) return -1;
    try {
        Engine temporary;
        Endpoint *e = temporary.endpoint(deviceId, channels, input);
        e->exclusive = 1;
        const std::string error = temporary.open(*e);
        return error.empty() ? 1 : 0;
    } catch (...) {
        return -1;
    }
}

// Warms AAudio's lazily loaded function pointers before concurrent probing.
extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_probe(JNIEnv *, jclass, jint deviceId, jboolean input) {
    std::lock_guard<std::mutex> lock(controlMutex);
    oboe::AudioStreamBuilder builder;
    builder.setAudioApi(oboe::AudioApi::AAudio)
        ->setDirection(input ? oboe::Direction::Input : oboe::Direction::Output)
        ->setDeviceId(deviceId)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setFormat(oboe::AudioFormat::Float);
    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) return static_cast<int>(result);
    const int channels =
        stream->getDeviceId() == deviceId ? stream->getChannelCount()
                                          : static_cast<int>(oboe::Result::ErrorDisconnected);
    stream->close();
    return channels;
}

// Opens a device and, for inputs, records a short window to find live channels.
// Returns [channels, error, frames, peak per channel].
extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_checkDevice(JNIEnv *env, jclass, jint deviceId, jboolean input, jint requested, jint listenMillis) {
    std::shared_ptr<oboe::AudioStream> stream;
    std::string error;
    int channels = std::clamp(static_cast<int>(requested), 1, kMaxChannels);
    int64_t frames = 0;
    std::vector<float> peaks(channels, 0.f);
    try {
        oboe::AudioStreamBuilder builder;
        builder.setAudioApi(oboe::AudioApi::AAudio)
            ->setDirection(input ? oboe::Direction::Input : oboe::Direction::Output)
            ->setDeviceId(deviceId)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setFormat(oboe::AudioFormat::Unspecified)
            ->setChannelCount(requested)
            ->setSampleRate(0)
            ->setInputPreset(oboe::InputPreset::Generic)
            ->setUsage(oboe::Usage::Game)
            ->setFormatConversionAllowed(false)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
        const oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            error = oboe::convertToText(result);
        } else if (stream->getDeviceId() != deviceId) {
            error = "AAudio routing: requested deviceId=" + std::to_string(deviceId) +
                    ", returned deviceId=" + std::to_string(stream->getDeviceId());
        } else {
            channels = stream->getChannelCount();
            if (channels < 1 || channels > kMaxChannels) {
                error = oboe::convertToText(oboe::Result::ErrorOutOfRange);
            } else {
                peaks.assign(channels, 0.f);
                const oboe::AudioFormat format = stream->getFormat();
                if (format != oboe::AudioFormat::Float && format != oboe::AudioFormat::I16 &&
                    format != oboe::AudioFormat::I24 && format != oboe::AudioFormat::I32) {
                    error = oboe::convertToText(oboe::Result::ErrorInvalidFormat);
                } else if (input) {
                    const oboe::Result started = stream->requestStart();
                    if (started != oboe::Result::OK) {
                        error = oboe::convertToText(started);
                    } else {
                        const int chunk = std::clamp(stream->getSampleRate() / 100, 1, 4096);
                        std::vector<int32_t> buffer(chunk * channels);
                        const auto deadline = std::chrono::steady_clock::now() +
                                              std::chrono::milliseconds(
                                                  std::clamp(static_cast<int>(listenMillis), 100, 3000));
                        while (error.empty() && std::chrono::steady_clock::now() < deadline) {
                            const auto read = stream->read(buffer.data(), chunk, 20000000);
                            if (!read) {
                                error = oboe::convertToText(read.error());
                                break;
                            }
                            frames += read.value();
                            for (int f = 0; f < read.value(); ++f) {
                                for (int c = 0; c < channels; ++c) {
                                    const float value = readPcm(buffer.data(), f * channels + c, format);
                                    if (!std::isfinite(value)) {
                                        error = oboe::convertToText(oboe::Result::ErrorInvalidFormat);
                                        break;
                                    }
                                    peaks[c] = std::max(peaks[c], std::abs(value));
                                }
                            }
                            if (read.value() == 0) std::this_thread::sleep_for(std::chrono::milliseconds(1));
                        }
                        if (error.empty() && frames == 0) {
                            error = oboe::convertToText(oboe::Result::ErrorTimeout);
                        }
                    }
                }
            }
        }
    } catch (const std::exception &e) {
        error = e.what();
    }
    if (stream) {
        if (input && stream->getState() != oboe::StreamState::Open) {
            const auto r = stream->stop(500000000);
            if (error.empty() && r != oboe::Result::OK) error = oboe::convertToText(r);
        }
        const auto r = stream->close();
        if (error.empty() && r != oboe::Result::OK) error = oboe::convertToText(r);
    }
    channels = std::clamp(channels, 1, kMaxChannels);
    peaks.resize(channels, 0.f);
    const jclass stringClass = env->FindClass("java/lang/String");
    const jobjectArray array = env->NewObjectArray(channels + 3, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    std::vector<std::string> values{std::to_string(channels), error, std::to_string(frames)};
    for (float peak : peaks) {
        std::ostringstream s;
        s.precision(9);
        s << peak;
        values.push_back(s.str());
    }
    for (size_t i = 0; i < values.size(); ++i) {
        const auto value = toJString(env, values[i]);
        env->SetObjectArrayElement(array, i, value);
        env->DeleteLocalRef(value);
    }
    return array;
}

// Returns [sampleRate, magnitudes in dBFS...] for the channel's recent history.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_spectrum(JNIEnv *env, jclass, jint deviceId, jint channel) {
    std::lock_guard<std::mutex> lock(controlMutex);
    Endpoint *input = nullptr;
    if (engine) {
        for (auto &e : engine->endpoints) {
            if (e->input && e->deviceId == deviceId && channel >= 0 && channel < e->channels) input = e.get();
        }
    }
    if (!input) return env->NewFloatArray(0);
    constexpr int N = kSpectrumFft;
    std::array<std::complex<float>, N> data;
    const auto write = input->historyWrite.load(std::memory_order_acquire);
    if (write < N) return env->NewFloatArray(0);
    for (int k = 0; k < N; ++k) {
        data[k] = (*input->history[channel])[(write - N + k) % kSpectrumHistory].load(
                      std::memory_order_relaxed) *
                  (0.5f - 0.5f * std::cos(2.f * 3.14159265f * k / (N - 1)));
    }
    for (int i = 1, j = 0; i < N; i++) {
        int bit = N >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(data[i], data[j]);
    }
    for (int len = 2; len <= N; len *= 2) {
        const auto step = std::polar(1.f, -2.f * 3.14159265f / len);
        for (int i = 0; i < N; i += len) {
            std::complex<float> z(1, 0);
            for (int j = 0; j < len / 2; j++) {
                const auto u = data[i + j];
                const auto v = data[i + j + len / 2] * z;
                data[i + j] = u + v;
                data[i + j + len / 2] = u - v;
                z *= step;
            }
        }
    }
    std::array<float, N / 2 + 1> result;
    result[0] = static_cast<float>(input->stream->getSampleRate());
    for (int k = 1; k <= N / 2; ++k) {
        result[k] = 20.f * std::log10(std::max(1e-6f, std::abs(data[k]) / (N / 4)));
    }
    const jfloatArray array = env->NewFloatArray(result.size());
    env->SetFloatArrayRegion(array, 0, result.size(), result.data());
    return array;
}

// Writes mono ringtone samples into a running output endpoint's test ring.
extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_writeTest(JNIEnv *env, jclass, jint deviceId, jint channel, jfloatArray mono, jint offset, jint frames) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (!engine || !engine->active.load()) return -895;
    if (!mono || offset < 0 || frames < 0 || frames > static_cast<int>(kRingCapacity) ||
        offset > env->GetArrayLength(mono) - frames) {
        return -898;
    }
    Endpoint *out = nullptr;
    for (auto &e : engine->endpoints) {
        if (!e->input && e->deviceId == deviceId) out = e.get();
    }
    if (!out || channel < 0 || channel >= out->channels) return -898;
    std::array<float, kRingCapacity> values{};
    env->GetFloatArrayRegion(mono, offset, frames, values.data());
    out->testChannel.store(channel);
    out->testDrainFrame = -1;
    int written = 0;
    for (; written < frames; ++written) {
        if (!out->testRing.push(values[written])) break;
    }
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_testPending(JNIEnv *, jclass, jint deviceId) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) {
        for (auto &e : engine->endpoints) {
            if (!e->input && e->deviceId == deviceId && e->stream) {
                const auto queued = e->testRing.available();
                if (queued > 0) return static_cast<jint>(queued);
                // Empty software ring does not mean its final callback has
                // reached the device. Keep the endpoint until that tail plays.
                if (e->testDrainFrame < 0) {
                    // Include a callback which emptied the ring but has not
                    // yet returned its buffer to AAudio.
                    e->testDrainFrame = e->stream->getFramesWritten() + e->stream->getFramesPerBurst();
                }
                const auto tail = e->testDrainFrame - e->stream->getFramesRead();
                return static_cast<jint>(std::max<int64_t>(0, tail));
            }
        }
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_outputRate(JNIEnv *, jclass, jint deviceId) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) {
        for (auto &e : engine->endpoints) {
            if (!e->input && e->deviceId == deviceId && e->stream) return e->stream->getSampleRate();
        }
    }
    return 0;
}

// Opens a single monitoring endpoint (spectrum preview) inside a live engine.
extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_monitorConfigured(JNIEnv *env, jclass, jintArray config) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (!engine || !engine->active.load()) return toJString(env, "");
    if (!config || env->GetArrayLength(config) != 7) return toJString(env, "ErrorIllegalArgument");
    int a[7];
    env->GetIntArrayRegion(config, 0, 7, a);
    const int deviceId = a[0], channels = a[2];
    if (deviceId <= 0 || channels < 1 || channels > kMaxChannels || a[1] < 0 || a[1] >= channels ||
        a[3] < 0 || a[3] > 384000 || a[4] < 0 || a[4] > 4 || a[5] < 0 || a[5] > 1) {
        return toJString(env, "ErrorIllegalArgument");
    }
    for (auto &e : engine->endpoints) {
        if (e->input && e->deviceId == deviceId) {
            if (e->channels >= channels && e->requestedRate == a[3] && e->requestedFormat == a[4] &&
                e->exclusive == a[5] && e->inputPreset == a[6]) {
                return toJString(env, "");
            }
            return toJString(env, "AAudio active input configuration differs from spectrum request");
        }
    }
    try {
        auto *e = engine->configured(a, true);
        const std::string error = engine->open(*e);
        if (!error.empty()) {
            engine->fail(oboe::Result::ErrorUnavailable);
            return toJString(env, error);
        }
        const oboe::Result r = e->stream->requestStart();
        if (r != oboe::Result::OK) {
            engine->fail(r);
            return toJString(env, oboe::convertToText(r));
        }
        return toJString(env, "");
    } catch (const std::exception &e) {
        engine->fail(oboe::Result::ErrorInternal);
        return toJString(env, e.what());
    }
}

// Fails the running engine with a given result code (instrumentation only).
extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_injectError(JNIEnv *, jclass, jint code) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) engine->fail(static_cast<oboe::Result>(code));
}

// Verifies the lock-free ring and PCM conversions without touching audio devices.
extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_selfTest(JNIEnv *env, jclass) {
    Ring ring;
    float x = 0;
    if (ring.pop(x)) return toJString(env, "FAIL empty");
    for (uint32_t i = 0; i < kRingCapacity; ++i) {
        if (!ring.push(static_cast<float>(i))) return toJString(env, "FAIL push");
    }
    if (ring.push(1)) return toJString(env, "FAIL overflow");
    for (uint32_t i = 0; i < kRingCapacity; ++i) {
        if (!ring.pop(x) || x != static_cast<float>(i)) return toJString(env, "FAIL order");
    }
    for (int k = 0; k < 100000; ++k) {
        if (!ring.push(static_cast<float>(k)) || !ring.pop(x) || x != static_cast<float>(k)) {
            return toJString(env, "FAIL wrap");
        }
    }
    Ring left, right;
    for (int k = 0; k < 1024; ++k) {
        left.push(static_cast<float>(k));
        right.push(static_cast<float>(-k));
    }
    left.trim(128);
    if (left.available() != 128 || right.available() != 1024) return toJString(env, "FAIL fanout");
    left.pop(x);
    if (x != 896.f) return toJString(env, "FAIL trim");
    right.pop(x);
    right.pop(x);
    if (x != -1.f) return toJString(env, "FAIL isolation");
    alignas(8) uint8_t raw[64];
    const float values[] = {-1.f, -.5f, 0.f, .5f, .999f};
    for (auto format : {oboe::AudioFormat::I16, oboe::AudioFormat::I24, oboe::AudioFormat::I32,
                        oboe::AudioFormat::Float}) {
        writePcm(raw, values, 5, format);
        for (int k = 0; k < 5; ++k) {
            if (std::abs(readPcm(raw, k, format) - values[k]) > .00004f) {
                return toJString(env, "FAIL PCM conversion");
            }
        }
    }
    return toJString(env, "PASS native FIFO and PCM16/24/32/Float conversion");
}

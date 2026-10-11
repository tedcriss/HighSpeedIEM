// Low-latency forwarding engine on top of Oboe / AAudio.
//
// Design constraints:
// - The audio callback performs no allocation, no locking, no JNI and no disk I/O.
//   Errors/settings and a waiting ringtone writer use coalesced nonblocking eventfd wakes.
// - Each physical device gets exactly one multi-channel stream; routes share it.
// - Each input with one output device is read directly by that output callback.
//   Only cross-output fanout uses SPSC rings, published/consumed once per block.
// - Input and output rates must match after the system negotiates both streams;
//   this engine never performs application-level sample-rate conversion.

#include <jni.h>
#include <oboe/Oboe.h>
#include "LatencyPolicy.h"

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
#include <sys/eventfd.h>
#include <unistd.h>
#include <cerrno>
#include <system_error>
#include <poll.h>

namespace {

constexpr uint32_t kRingCapacity = 16384;
constexpr uint32_t kSpectrumHistory = 1024;
constexpr int kMaxChannels = 32;
constexpr int kMaxRoutes = 128;
// Upper bound for the pinned output buffer, in bursts (0 keeps adaptive tuning).
constexpr int kMaxBufferBursts = 32;
constexpr int kSpectrumFft = 512;

int captureFramesToDiscard(int available, int callbackFrames, int inputBurst, int outputBurst,
                           int cushion = 1) {
    // The input/output driver periods may differ. A burst of scheduling headroom
    // prevents repeatedly dropping the very frames needed by the next callback.
    const int keep = callbackFrames + std::max(inputBurst, outputBurst) * cushion;
    return std::min(static_cast<int>(kRingCapacity), std::max(0, available - keep));
}

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

    // One publication per block instead of per sample. Only the consumer owns read.
    uint32_t pushChannel(const float *samples, uint32_t frames, int channels, int channel) {
        const auto w = write.load(std::memory_order_relaxed);
        const auto space = kRingCapacity - (w - read.load(std::memory_order_acquire));
        const auto count = std::min(frames, space);
        for (uint32_t f = 0; f < count; ++f) data[(w + f) % kRingCapacity] = samples[f * channels + channel];
        write.store(w + count, std::memory_order_release);
        return count;
    }

    uint32_t mix(float *samples, uint32_t frames, int channels, int channel) {
        const auto r = read.load(std::memory_order_relaxed);
        const auto count = std::min(frames, write.load(std::memory_order_acquire) - r);
        for (uint32_t f = 0; f < count; ++f) samples[f * channels + channel] += data[(r + f) % kRingCapacity];
        read.store(r + count, std::memory_order_release);
        return count;
    }

    uint32_t available() const {
        const auto r = read.load(std::memory_order_acquire);
        return std::min(kRingCapacity, write.load(std::memory_order_acquire) - r);
    }

    // Drop the oldest samples so a scheduling stall cannot become permanent delay.
    uint32_t trim(uint32_t keep) {
        const auto w = write.load(std::memory_order_acquire);
        const auto r = read.load(std::memory_order_relaxed);
        if (w - r <= keep) return 0;
        read.store(w - keep, std::memory_order_release);
        return w - r - keep;
    }
};

struct Engine;
struct Route;

// Error threads can outlive Engine::stop(). Never let them dereference Engine.
struct EngineSignal {
    inline static std::atomic<int64_t> nextId{0};
    const int64_t id = ++nextId;
    const int waitFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    const int spaceFd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    std::atomic<bool> spaceWaiting{false}, testCancelled{false};
    std::atomic<int> pending{2}; // error=1, settings=2
    std::atomic<bool> closed{false};
    std::atomic<int> error{0};
    std::atomic<bool> active{false};
    EngineSignal() {
        if (waitFd < 0 || spaceFd < 0) {
            const int saved = errno;
            if (waitFd >= 0) ::close(waitFd);
            if (spaceFd >= 0) ::close(spaceFd);
            throw std::system_error(saved, std::generic_category(), "eventfd");
        }
    }
    ~EngineSignal() { ::close(waitFd); ::close(spaceFd); }
    void wakeSpace() {
        if (spaceWaiting.exchange(false)) {
            const uint64_t one = 1;
            while (::write(spaceFd, &one, sizeof(one)) < 0 && errno == EINTR) {}
        }
    }
    void notify(int flags) {
        // One bounded kernel wake only on the transition to pending. No JNI,
        // heap allocation or user-space lock on the audio thread.
        if (pending.fetch_or(flags, std::memory_order_release) == 0) {
            const uint64_t one = 1;
            while (::write(waitFd, &one, sizeof(one)) < 0 && errno == EINTR) {}
        }
    }
    void finish() {
        if (!closed.exchange(true)) {
            testCancelled.store(true);
            wakeSpace();
            const uint64_t one = 1;
            while (::write(waitFd, &one, sizeof(one)) < 0 && errno == EINTR) {}
        }
    }
    void fail(oboe::Result result) {
        int expected = 0;
        active.store(false);
        if (error.compare_exchange_strong(expected, static_cast<int>(result))) {
            notify(1);
            wakeSpace();
        }
    }
};

// One opened stream per physical device endpoint.
struct Endpoint : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback,
                  std::enable_shared_from_this<Endpoint> {
    Engine *engine;
    std::shared_ptr<EngineSignal> signal;
    int deviceId;
    int channels;
    bool input;
    int requestedRate = 0;   // 0 = let the system choose
    int requestedFormat = 0; // 0 = let the system choose
    int exclusive = 0;
    int inputPreset = 0;
    // 0 = adaptive LatencyTuner; >0 pins the stream buffer to that many bursts.
    int requestedBufferBursts = 0;
    std::vector<float> scratch;
    std::vector<uint8_t> capture;
    Endpoint *pullOwner = nullptr;
    // Fixed before start; callbacks never traverse a control-thread-mutated vector.
    std::vector<Endpoint *> pulledInputs;
    std::vector<Route *> inputRoutes;
    std::vector<Route *> outputRoutes;
    std::atomic<uint64_t> dropped{0};
    std::atomic<int32_t> queued{0};
    std::atomic<int32_t> callbackFrames{0};
    std::atomic<int> pullCushion{1};
    InputGuard guard;
    std::atomic<int> guardFrames{0};
    std::atomic<uint32_t> shortReads{0}, reprimes{0}, guardProbes{0}, guardFailures{0};
    std::atomic<int> guardCooldown{10};
    bool pullPrimed = false;
    uint64_t stableInputFrames = 0;
    int minimumPullHeadroom = INT32_MAX;
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
    int32_t previousXRuns = 0;
    uint64_t stableOutputFrames = 0;
    OutputProbe outputProbe;
    std::atomic<int> outputMinimum{0}, outputCooldown{15};
    std::atomic<uint32_t> outputReductions{0}, outputFailures{0};
    uint64_t lastFrames = 0;
    std::string configSnapshot;
    std::array<int, 4> publishedSettings{{-1, -1, -1, -1}}; // callback-owned
    void publishSettings() {
        const std::array<int, 4> current{{stream->getBufferSizeInFrames(),
            guardFrames.load(), pullCushion.load(), outputMinimum.load()}};
        if (current != publishedSettings) {
            publishedSettings = current;
            signal->notify(2);
        }
    }
    std::chrono::steady_clock::time_point lastProgress = std::chrono::steady_clock::now();

    Endpoint(Engine *e, std::shared_ptr<EngineSignal> state, int device, int channelCount, bool isInput)
        : engine(e), signal(std::move(state)), deviceId(device), channels(channelCount), input(isInput) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) override;
    bool onError(oboe::AudioStream *stream, oboe::Result error) override;
    bool capturePcm(const void *buffer, int numFrames);
    int pull(int numFrames);
    int pullStable(int numFrames);
};

// One forwarding pair. A private ring is needed only for cross-output fanout.
struct Route {
    Endpoint *in;
    Endpoint *out;
    int inChannel;
    int outChannel;
    std::unique_ptr<Ring> ring;
    bool primed = false;
    std::atomic<int> cushion{0};
    int publishedCushion = -1, publishedGuard = -1; // output callback only
    bool settingsChanged() {
        const int nextCushion = cushion.load(), nextGuard = guardFrames.load();
        const bool changed = publishedCushion != nextCushion || publishedGuard != nextGuard;
        publishedCushion = nextCushion;
        publishedGuard = nextGuard;
        return changed;
    }
    std::atomic<uint64_t> underflows{0};
    uint64_t stableFrames = 0;
    bool minimumLatency = false;
    InputGuard guard;
    std::atomic<int> guardFrames{0};
    std::atomic<uint32_t> trimmed{0};

    Route(Endpoint *i, Endpoint *o, int ic, int oc) : in(i), out(o), inChannel(ic), outChannel(oc) {}

    uint32_t mix(float *samples, uint32_t numFrames, int inputBurst, int sampleRate) {
        if (minimumLatency) {
            const int outputBurst = out->stream ? out->stream->getFramesPerBurst() : numFrames;
            guard.prepare(numFrames, kRingCapacity, inputBurst, outputBurst, sampleRate);
            trimmed.fetch_add(ring->trim(numFrames + guard.frames), std::memory_order_relaxed);
            if (!guard.ready(ring->available(), numFrames)) {
                guardFrames.store(guard.frames, std::memory_order_relaxed);
                return 0;
            }
            const auto count = ring->mix(samples, numFrames, out->channels, outChannel);
            guard.completed(numFrames, count, sampleRate);
            if (count < numFrames) underflows.fetch_add(1, std::memory_order_relaxed);
            guardFrames.store(guard.frames, std::memory_order_relaxed);
            return count;
        }
        const auto burst = static_cast<uint32_t>(std::max(1, inputBurst));
        int extra = cushion.load(std::memory_order_relaxed);
        // Recover after a transient stall, gradually, rather than retaining it forever.
        if (extra > 0 && stableFrames >= static_cast<uint64_t>(sampleRate) * 10) {
            cushion.store(--extra, std::memory_order_relaxed);
            stableFrames = 0;
        }
        const auto target = std::min(kRingCapacity, numFrames + burst * extra);
        ring->trim(std::min(kRingCapacity, target + burst));
        if (!primed) {
            if (ring->available() < target) return 0;
            primed = true;
        }
        const auto count = ring->mix(samples, numFrames, out->channels, outChannel);
        if (count < numFrames) {
            primed = false;
            underflows.fetch_add(1, std::memory_order_relaxed);
            cushion.store(std::min(8, extra + 1), std::memory_order_relaxed);
            stableFrames = 0;
        } else {
            stableFrames += count;
        }
        return count;
    }
};

struct Engine {
#ifndef NDEBUG
    bool freezeProgressForTest = false; // serial control thread only
#endif
    // 0 = 3.1 baseline, 1 = zero input guard/two output bursts,
    // 2 = minimum latency: zero input guard/one output burst (default).
    int latencyMode = 2;
    std::vector<std::shared_ptr<Endpoint>> endpoints;
    std::vector<std::unique_ptr<Route>> routes;
    std::shared_ptr<EngineSignal> signal = std::make_shared<EngineSignal>();
    std::atomic<int> &error = signal->error;
    std::atomic<bool> &active = signal->active;

    void fail(oboe::Result result) {
        signal->fail(result);
    }

    void prepareRoutes() {
        for (auto &r : routes) {
            r->minimumLatency = latencyMode != 0;
            r->in->inputRoutes.push_back(r.get());
            r->out->outputRoutes.push_back(r.get());
        }
        for (auto &e : endpoints) {
            if (!e->input || e->inputRoutes.empty()) continue;
            auto *owner = e->inputRoutes.front()->out;
            const bool singleOutput = std::all_of(e->inputRoutes.begin(), e->inputRoutes.end(),
                [owner](const Route *r) { return r->out == owner; });
            if (singleOutput) {
                e->pullOwner = owner;
                owner->pulledInputs.push_back(e.get());
            } else {
                for (auto *r : e->inputRoutes) r->ring = std::make_unique<Ring>();
            }
        }
    }

    Endpoint *endpoint(int deviceId, int channels, bool input) {
        for (auto &e : endpoints) {
            if (e->deviceId == deviceId && e->input == input) {
                e->channels = std::max(e->channels, channels);
                return e.get();
            }
        }
        endpoints.push_back(std::make_shared<Endpoint>(this, signal, deviceId, channels, input));
        return endpoints.back().get();
    }

    // Registers an endpoint from a route spec; rejects conflicting configurations
    // when the same device appears in multiple routes with different parameters.
    Endpoint *configured(const int *spec, bool input) {
        if (spec[0] == -1) return nullptr;
        for (auto &e : endpoints) {
            if (e->deviceId == spec[0] && e->input == input &&
                (e->requestedRate != spec[3] || e->requestedFormat != spec[4] ||
                 e->exclusive != spec[5] || e->inputPreset != spec[6] ||
                 e->requestedBufferBursts != spec[7])) {
                throw std::invalid_argument("AAudio same-device configurations differ");
            }
        }
        Endpoint *e = endpoint(spec[0], spec[2], input);
        e->requestedRate = spec[3];
        e->requestedFormat = spec[4];
        e->exclusive = spec[5];
        e->inputPreset = spec[6];
        e->requestedBufferBursts = spec[7];
        return e;
    }

    void stop() {
        active.store(false);
        signal->finish();
        // Streams must be quiescent before callbacks and rings are destroyed.
        for (auto &e : endpoints) {
            if (e->stream) e->stream->stop(1000000000);
        }
        for (auto &e : endpoints) {
            if (e->stream) {
                e->stream->close();
            }
        }
        // A callback already executing on another endpoint can still reference
        // a route's input/output stream. Keep all stream objects until ALL close.
        for (auto &e : endpoints) e->stream.reset();
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
            ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(e.shared_from_this()))
            ->setUsage(oboe::Usage::Game)
            ->setInputPreset(e.input && e.inputPreset == 0 ? oboe::InputPreset::VoicePerformance
                                                         : static_cast<oboe::InputPreset>(e.inputPreset));
        if (!e.pullOwner) {
            builder.setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(e.shared_from_this()));
        } else {
            // Capacity is headroom for driver scheduling, not queued latency.
            builder.setBufferCapacityInFrames(std::min(static_cast<int>(kRingCapacity),
                e.pullOwner->stream->getBufferCapacityInFrames() * 2));
        }
        oboe::Result result = builder.openStream(e.stream);
        if (result != oboe::Result::OK && e.input && e.inputPreset == 0) {
            // Some vendor policies reject the performance preset. Automatic
            // mode can use the speech-recognition path; explicit choices stay strict.
            if (e.stream) e.stream->close();
            e.stream.reset();
            builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
            result = builder.openStream(e.stream);
        }
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
        if (e.pullOwner) e.capture.resize(kRingCapacity * e.channels * sizeof(float));
        if (!e.input) {
            if (e.requestedBufferBursts > 0) {
                // Pinned buffer: apply once, device-clamped, no adaptive tuning.
                const int requested = e.stream->getFramesPerBurst() * e.requestedBufferBursts;
                const auto size = e.stream->setBufferSizeInFrames(
                    std::min(requested, e.stream->getBufferCapacityInFrames()));
                if (!size) return oboe::convertToText(size.error());
                e.outputProbe.floor = size.value();
                e.outputMinimum.store(size.value());
            } else {
                e.tuner = std::make_unique<oboe::LatencyTuner>(*e.stream);
                const int minimum = e.stream->getFramesPerBurst() *
                    (latencyMode == 2 && !e.outputRoutes.empty() ? 1 : 2);
                e.tuner->setMinimumBufferSize(minimum);
                // The constructor resets to two bursts. Apply the new minimum via
                // the tuner's own reset path before starting the output callback.
                e.tuner->requestReset();
                const auto tuning = e.tuner->tune();
                if (tuning != oboe::Result::OK && tuning != oboe::Result::ErrorUnimplemented)
                    return oboe::convertToText(tuning);
                auto size = e.stream->setBufferSizeInFrames(minimum);
                if (!size) return oboe::convertToText(size.error());
                e.outputProbe.floor = size.value(); // actual device-clamped minimum
                e.outputMinimum.store(size.value());
            }
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
    signal->fail(result);
    return true;
}

bool Endpoint::capturePcm(const void *buffer, int numFrames) {
    const auto format = stream->getFormat();
    for (int k = 0; k < numFrames * channels; ++k) {
        scratch[k] = readPcm(buffer, k, format);
        if (!std::isfinite(scratch[k])) {
            signal->fail(oboe::Result::ErrorInvalidFormat);
            return false;
        }
    }
    const auto position = historyWrite.load(std::memory_order_relaxed);
    // A large input block only needs its most recent spectrum window copied.
    for (int f = std::max(0, numFrames - static_cast<int>(kSpectrumHistory)); f < numFrames; ++f) {
        for (int c = 0; c < channels; ++c) {
            (*history[c])[(position + f) % kSpectrumHistory].store(scratch[f * channels + c],
                                                                  std::memory_order_relaxed);
        }
    }
    historyWrite.store(position + numFrames, std::memory_order_release);
    frames.fetch_add(numFrames, std::memory_order_relaxed);
    return true;
}

int Endpoint::pullStable(int numFrames) {
    if (stream->getDeviceId() != deviceId) {
        signal->fail(oboe::Result::ErrorDisconnected);
        return 0;
    }
    auto available = stream->getAvailableFrames();
    if (!available) {
        signal->fail(available.error());
        return 0;
    }
    // One driver burst normally suffices. Poor driver scheduling can require
    // more; build that headroom in the native buffer, not an application FIFO.
    const int burst = std::max(stream->getFramesPerBurst(), pullOwner->stream->getFramesPerBurst());
    const int capacity = stream->getBufferCapacityInFrames();
    const int limit = std::clamp((capacity - numFrames) / std::max(1, burst), 1, 4);
    int cushion = std::min(pullCushion.load(std::memory_order_relaxed), limit);
    if (cushion > 1 && stableInputFrames >= static_cast<uint64_t>(stream->getSampleRate()) * 30) {
        // Do not trade regular dropouts for a smaller number on the screen:
        // only shrink when the entire stable window had spare native headroom.
        if (minimumPullHeadroom >= burst + std::max(numFrames, burst / 4)) --cushion;
        stableInputFrames = 0;
        minimumPullHeadroom = INT32_MAX;
    }
    pullCushion.store(cushion, std::memory_order_relaxed);
    if (!pullPrimed) {
        if (available.value() < std::min(capacity, numFrames + burst * cushion)) {
            queued.store(available.value(), std::memory_order_relaxed);
            gaps.fetch_add(numFrames, std::memory_order_relaxed);
            return 0;
        }
        pullPrimed = true;
    }
    const int discard = captureFramesToDiscard(available.value(), numFrames,
        stream->getFramesPerBurst(), pullOwner->stream->getFramesPerBurst(), cushion);
    int discarded = 0;
    if (discard > 0) {
        auto read = stream->read(capture.data(), discard, 0);
        if (!read) {
            signal->fail(read.error());
            return 0;
        }
        discarded = read.value();
        dropped.fetch_add(read.value(), std::memory_order_relaxed);
    }
    auto read = stream->read(capture.data(), numFrames, 0); // Never block a real-time callback.
    if (!read) {
        signal->fail(read.error());
        return 0;
    }
    const int count = read.value();
    if (count < numFrames) {
        pullCushion.store(std::min(limit, cushion + 1), std::memory_order_relaxed);
        stableInputFrames = 0;
        minimumPullHeadroom = INT32_MAX;
        pullPrimed = false;
    } else {
        stableInputFrames += count;
    }
    const int headroom = std::max(0, available.value() - discarded - count);
    if (count == numFrames) minimumPullHeadroom = std::min(minimumPullHeadroom, headroom);
    queued.store(headroom, std::memory_order_relaxed);
    gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
    return capturePcm(capture.data(), count) ? count : 0;
}

int Endpoint::pull(int numFrames) {
    callbackFrames.store(numFrames, std::memory_order_relaxed);
    if (engine->latencyMode == 0) return pullStable(numFrames);
    if (stream->getDeviceId() != deviceId) {
        signal->fail(oboe::Result::ErrorDisconnected);
        return 0;
    }
    auto available = stream->getAvailableFrames();
    if (!available) {
        signal->fail(available.error());
        return 0;
    }
    const int rate = stream->getSampleRate();
    guard.prepare(numFrames, stream->getBufferCapacityInFrames(), stream->getFramesPerBurst(),
                  pullOwner->stream->getFramesPerBurst(), rate);
    const auto publishGuard = [&] {
        guardFrames.store(guard.frames, std::memory_order_relaxed);
        shortReads.store(guard.shortReads, std::memory_order_relaxed);
        reprimes.store(guard.reprimes, std::memory_order_relaxed);
        guardProbes.store(guard.probes, std::memory_order_relaxed);
        guardFailures.store(guard.failedProbes, std::memory_order_relaxed);
        guardCooldown.store(guard.cooldownSeconds, std::memory_order_relaxed);
    };
    if (!guard.ready(available.value(), numFrames)) {
        queued.store(available.value(), std::memory_order_relaxed);
        gaps.fetch_add(numFrames, std::memory_order_relaxed);
        publishGuard();
        return 0;
    }
    const int discard = guard.discard(available.value(), numFrames, kRingCapacity);
    int discarded = 0;
    if (discard > 0) {
        auto read = stream->read(capture.data(), discard, 0);
        if (!read) {
            signal->fail(read.error());
            return 0;
        }
        discarded = read.value();
        dropped.fetch_add(discarded, std::memory_order_relaxed);
    }
    auto read = stream->read(capture.data(), numFrames, 0);
    if (!read) {
        signal->fail(read.error());
        return 0;
    }
    const int count = read.value();
    guard.completed(numFrames, count, rate);
    queued.store(std::max(0, available.value() - discarded - count), std::memory_order_relaxed);
    gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
    publishGuard();
    return capturePcm(capture.data(), count) ? count : 0;
}

oboe::DataCallbackResult Endpoint::onAudioReady(oboe::AudioStream *s, void *buffer, int32_t numFrames) {
    callbackFrames.store(numFrames, std::memory_order_relaxed);
    float *samples = scratch.data();
    if (numFrames < 0 || static_cast<uint32_t>(numFrames) > kRingCapacity) {
        signal->fail(oboe::Result::ErrorOutOfRange);
        return oboe::DataCallbackResult::Stop;
    }
    if (!signal->active.load(std::memory_order_acquire)) {
        if (!input) std::memset(buffer, 0, static_cast<size_t>(numFrames) * channels * s->getBytesPerSample());
        return oboe::DataCallbackResult::Stop;
    }
    if (s->getDeviceId() != deviceId) {
        engine->fail(oboe::Result::ErrorDisconnected);
        return oboe::DataCallbackResult::Stop;
    }
    if (input) {
        if (!capturePcm(buffer, numFrames)) return oboe::DataCallbackResult::Stop;
        for (auto *r : inputRoutes) {
            const auto count = r->ring->pushChannel(samples, numFrames, channels, r->inChannel);
            dropped.fetch_add(numFrames - count, std::memory_order_relaxed);
        }
    } else {
        const auto gapsBefore = gaps.load(std::memory_order_relaxed);
        std::fill(samples, samples + numFrames * channels, 0.f);
        testRing.mix(samples, numFrames, channels, testChannel.load(std::memory_order_relaxed));
        if (outputRoutes.empty()) signal->wakeSpace(); // only a waiting ringtone writer
        for (auto *in : pulledInputs) {
            const int count = in->pull(numFrames);
            in->publishSettings();
            for (auto *r : in->inputRoutes) {
                for (int f = 0; f < count; ++f) {
                    samples[f * channels + r->outChannel] += in->scratch[f * in->channels + r->inChannel];
                }
            }
            gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
        }
        for (auto *r : outputRoutes) {
            if (!r->ring) continue;
            const auto count = r->mix(samples, numFrames, r->in->stream->getFramesPerBurst(), s->getSampleRate());
            gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
        }
        writePcm(buffer, samples, numFrames * channels, s->getFormat());
        // Counters stay live even with a pinned buffer (no tuner instance).
        const auto count = s->getXRunCount();
        if (count) xruns.store(count.value(), std::memory_order_relaxed);
        if (tuner) {
            const auto result = tuner->tune();
            if (result != oboe::Result::OK && result != oboe::Result::ErrorUnimplemented) signal->fail(result);
            if (count) {
                if (engine->latencyMode != 0) {
                    const int target = outputProbe.update(s->getBufferSizeInFrames(), s->getFramesPerBurst(),
                        s->getSampleRate(), numFrames, count.value() != previousXRuns,
                        gaps.load(std::memory_order_relaxed) != gapsBefore);
                    previousXRuns = count.value();
                    if (target > 0) {
                        tuner->setMinimumBufferSize(target);
                        tuner->requestReset();
                    } else if (target < 0) {
                        tuner->setMinimumBufferSize(-target);
                    }
                    outputMinimum.store(tuner->getMinimumBufferSize(), std::memory_order_relaxed);
                    outputCooldown.store(outputProbe.cooldownSeconds, std::memory_order_relaxed);
                    outputReductions.store(outputProbe.reductions, std::memory_order_relaxed);
                    outputFailures.store(outputProbe.failedReductions, std::memory_order_relaxed);
                } else {
                    if (count.value() != previousXRuns || gaps.load(std::memory_order_relaxed) != gapsBefore) {
                        previousXRuns = count.value();
                        stableOutputFrames = 0;
                    } else {
                        stableOutputFrames += numFrames;
                    }
                    if (stableOutputFrames >= static_cast<uint64_t>(s->getSampleRate()) * 30) {
                        stableOutputFrames = 0;
                        // Use Oboe's own reset path, only after prolonged stable playback.
                        if (s->getBufferSizeInFrames() > tuner->getMinimumBufferSize()) tuner->requestReset();
                    }
                }
            }
        }
        frames.fetch_add(numFrames, std::memory_order_relaxed);
    }
    for (auto *r : outputRoutes) {
        if (r->ring && r->settingsChanged()) signal->notify(2);
    }
    publishSettings();
    return signal->active.load() ? oboe::DataCallbackResult::Continue : oboe::DataCallbackResult::Stop;
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
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_startNative(JNIEnv *env, jclass, jintArray spec, jint latencyMode) {
    std::lock_guard<std::mutex> lock(controlMutex);
    try {
        engine.reset();
        if (!spec || latencyMode < 0 || latencyMode > 2) return toJString(env, "ErrorIllegalArgument");
        const int length = env->GetArrayLength(spec);
        // 8 ints per endpoint: id, channel, channels, rate, format, exclusive,
        // preset, bufferBursts.
        if (length == 0 || length % 16 || length > 16 * kMaxRoutes) {
            return toJString(env, oboe::convertToText(oboe::Result::ErrorIllegalArgument));
        }
        std::vector<jint> a(length);
        env->GetIntArrayRegion(spec, 0, length, a.data());
        engine = std::make_unique<Engine>();
        engine->latencyMode = latencyMode;
        for (int k = 0; k < length; k += 16) {
            for (int side : {0, 8}) {
                const int x = k + side;
                if (a[x] != -1 && (a[x] <= 0 || a[x + 1] < 0 || a[x + 2] < 1 || a[x + 2] > kMaxChannels ||
                                   a[x + 1] >= a[x + 2] || a[x + 3] < 0 || a[x + 3] > 384000 ||
                                   a[x + 4] < 0 || a[x + 4] > 4 || a[x + 5] < 0 || a[x + 5] > 1 ||
                                   a[x + 7] < 0 || a[x + 7] > kMaxBufferBursts)) {
                    engine.reset();
                    return toJString(env, oboe::convertToText(oboe::Result::ErrorIllegalArgument));
                }
            }
            Endpoint *in = engine->configured(&a[k], true);
            Endpoint *out = engine->configured(&a[k + 8], false);
            if (in && out) {
                engine->routes.push_back(std::make_unique<Route>(in, out, a[k + 1], a[k + 9]));
            }
            if (!in && !out) {
                engine.reset();
                return toJString(env, "ErrorIllegalArgument");
            }
        }
        engine->prepareRoutes();
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
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_stopNative(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    engine.reset();
}


namespace {
void checkProgress() {
    if (!engine || engine->error.load() || !engine->active.load()) return;
    const auto now = std::chrono::steady_clock::now();
    for (auto &e : engine->endpoints) {
        auto frames = e->frames.load();
#ifndef NDEBUG
        if (engine->freezeProgressForTest) frames = e->lastFrames;
#endif
        if (frames != e->lastFrames) {
            e->lastFrames = frames;
            e->lastProgress = now;
        } else if (now - e->lastProgress > std::chrono::seconds(2)) {
            engine->fail(oboe::Result::ErrorTimeout);
            break;
        }
    }
}

// Fixed getters are evaluated once per opened endpoint. Runtime settings and
// continuous counters have separate readers; the UI never parses this text.
std::string endpointData(Endpoint &e, int part) {
    auto *s = e.stream.get();
    std::ostringstream text;
    if (part == 0) {
        if (e.configSnapshot.empty()) {
            text << s->getSampleRate() << " Hz · " << oboe::convertToText(s->getFormat()) << " · "
                 << s->getChannelCount() << " ch · " << oboe::convertToText(s->getSharingMode()) << " / "
                 << oboe::convertToText(s->getPerformanceMode()) << "\nburst=" << s->getFramesPerBurst()
                 << " · capacity=" << s->getBufferCapacityInFrames() << " · policy=" << engine->latencyMode
                 << "\ntransfer=" << (e.pullOwner ? "output-pull" : e.input ? "input-callback" : "output-callback");
            if (e.input) text << " · preset=" << static_cast<int>(s->getInputPreset());
            if (e.pullOwner) text << " · appQueue=0";
            if (s->getHardwareSampleRate() > 0) {
                text << "\nhardware: " << s->getHardwareSampleRate() << " Hz · "
                     << oboe::convertToText(s->getHardwareFormat()) << " · " << s->getHardwareChannelCount() << " ch";
            }
            e.configSnapshot = text.str();
        }
        return e.configSnapshot;
    }
    if (part == 1) {
        text << "buffer=" << s->getBufferSizeInFrames();
        if (e.requestedBufferBursts) text << " · bufferBursts=" << e.requestedBufferBursts;
        if (e.pullOwner) text << " · cushion=" << e.pullCushion.load() << " · guard=" << e.guardFrames.load();
        if (!e.input) text << " · outputTarget=" << e.outputMinimum.load();
        for (auto *r : e.outputRoutes) if (r->ring) {
            text << "\ncushion=" << r->cushion.load() << " · routeGuard=" << r->guardFrames.load();
        }
    } else {
        auto count = s->getXRunCount();
        if (count) e.xruns.store(count.value());
        text << "frames=" << e.frames.load() << " · xrun=" << e.xruns.load()
             << " · gap=" << e.gaps.load() << " · dropped=" << e.dropped.load()
             << " · callback=" << e.callbackFrames.load();
        if (e.pullOwner) text << " · queued=" << e.queued.load()
            << " · shortReads=" << e.shortReads.load() << " · reprimes=" << e.reprimes.load()
            << " · guardProbes=" << e.guardProbes.load() << " · guardFailures=" << e.guardFailures.load()
            << " · guardCooldown=" << e.guardCooldown.load();
        if (!e.input) text << " · outputProbes=" << e.outputReductions.load()
            << " · outputFailures=" << e.outputFailures.load() << " · outputCooldown=" << e.outputCooldown.load();
        for (auto *r : e.outputRoutes) if (r->ring) {
            text << "\nrouteQueue=" << r->ring->available() << " · routeDropped=" << r->trimmed.load()
                 << " · routeUnderflows=" << r->underflows.load();
        }
    }
    return text.str();
}
}

extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_checkHealth(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    checkProgress(); // ErrorTimeout uses exactly the same event path as Oboe.
}

#ifndef NDEBUG
extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_test_NativeEventTestApi_freezeProgress(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (!engine) return;
    engine->freezeProgressForTest = true;
    for (auto &e : engine->endpoints) {
        e->lastFrames = e->frames.load();
        e->lastProgress = std::chrono::steady_clock::now();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_test_NativeEventTestApi_resizeBuffer(JNIEnv *, jclass, jint deviceId, jint frames) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) for (auto &e : engine->endpoints) {
        if (!e->input && e->deviceId == deviceId) {
            const int before = e->stream->getBufferSizeInFrames();
            const auto actual = e->stream->setBufferSizeInFrames(frames);
            if (!actual) return static_cast<int>(actual.error());
            if (actual.value() != before) engine->signal->notify(2);
            return actual.value();
        }
    }
    return -1;
}
#endif

// Diagnostic compatibility only. Production control observes awaitEvent.
extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_pollError(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    checkProgress();
    return toJString(env, stopError());
}

extern "C" JNIEXPORT jlong JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_sessionId(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    return engine ? engine->signal->id : 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_awaitEvent(JNIEnv *env, jclass, jlong id) {
    std::shared_ptr<EngineSignal> signal;
    {
        std::lock_guard<std::mutex> lock(controlMutex);
        if (engine && engine->signal->id == id) signal = engine->signal;
    }
    auto strings = env->FindClass("java/lang/String");
    if (!signal) return env->NewObjectArray(0, strings, nullptr);
    int flags = 0;
    while (!signal->closed.load(std::memory_order_acquire)) {
        flags = signal->pending.exchange(0, std::memory_order_acquire);
        if (flags) break;
        pollfd fd{signal->waitFd, POLLIN, 0};
        int result;
        do { result = ::poll(&fd, 1, -1); } while (result < 0 && errno == EINTR);
        if (result < 0) {
            auto exception = env->FindClass("java/io/IOException");
            env->ThrowNew(exception, std::strerror(errno));
            return nullptr;
        }
        uint64_t count;
        while (::read(signal->waitFd, &count, sizeof(count)) < 0 && errno == EINTR) {}
    }
    if (signal->closed.load()) return env->NewObjectArray(0, strings, nullptr);
    auto result = env->NewObjectArray(2, strings, nullptr);
    const int code = (flags & 1) ? signal->error.load(std::memory_order_acquire) : 0;
    const std::string error = code ? std::string(oboe::convertToText(static_cast<oboe::Result>(code)))
        + " (" + std::to_string(code) + ")" : "";
    env->SetObjectArrayElement(result, 0, toJString(env, std::to_string(flags)));
    env->SetObjectArrayElement(result, 1, toJString(env, error));
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_endpointPart(JNIEnv *env, jclass, jint deviceId, jboolean input, jint part) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) for (auto &e : engine->endpoints) {
        if (e->deviceId == deviceId && e->input == static_cast<bool>(input) && e->stream) {
            return toJString(env, endpointData(*e, part));
        }
    }
    return toJString(env, "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_endpointStats(JNIEnv *env, jclass, jint deviceId, jboolean input) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) for (auto &e : engine->endpoints) {
        if (e->deviceId == deviceId && e->input == static_cast<bool>(input) && e->stream) {
            return toJString(env, endpointData(*e, 0) + "\n" + endpointData(*e, 1) + "\n" + endpointData(*e, 2));
        }
    }
    return toJString(env, "");
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

// Open-only validation: silence and capture policy are not device failures.
// Returns [channels, error]; never starts capture or installs callbacks.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_checkDevice(JNIEnv *env, jclass, jint deviceId, jboolean input, jint requested) {
    std::lock_guard<std::mutex> lock(controlMutex);
    std::shared_ptr<oboe::AudioStream> stream;
    std::string error;
    int channels = std::clamp(static_cast<int>(requested), 1, kMaxChannels);
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
                const oboe::AudioFormat format = stream->getFormat();
                if (format != oboe::AudioFormat::Float && format != oboe::AudioFormat::I16 &&
                    format != oboe::AudioFormat::I24 && format != oboe::AudioFormat::I32) {
                    error = oboe::convertToText(oboe::Result::ErrorInvalidFormat);
                }
            }
        }
    } catch (const std::exception &e) {
        error = e.what();
    }
    if (stream) {
        const auto r = stream->close();
        if (error.empty() && r != oboe::Result::OK) error = oboe::convertToText(r);
    }
    channels = std::clamp(channels, 1, kMaxChannels);
    const jclass stringClass = env->FindClass("java/lang/String");
    const jobjectArray array = env->NewObjectArray(2, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    std::vector<std::string> values{std::to_string(channels), error};
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
    constexpr int N = kSpectrumFft;
    static const auto window = [] {
        std::array<float, N> result{};
        for (int k = 0; k < N; ++k)
            result[k] = 0.5f - 0.5f * std::cos(2.f * 3.14159265f * k / (N - 1));
        return result;
    }();
    std::array<std::complex<float>, N> data;
    float sampleRate;
    {
        // Copy the atomic history while its endpoint is owned. The FFT does not
        // access the engine and must not hold up stop/start or other control APIs.
        std::lock_guard<std::mutex> lock(controlMutex);
        Endpoint *input = nullptr;
        if (engine) {
            for (auto &e : engine->endpoints) {
                if (e->input && e->deviceId == deviceId && channel >= 0 && channel < e->channels) input = e.get();
            }
        }
        if (!input) return env->NewFloatArray(0);
        const auto write = input->historyWrite.load(std::memory_order_acquire);
        if (write < N) return env->NewFloatArray(0);
        for (int k = 0; k < N; ++k) {
            data[k] = (*input->history[channel])[(write - N + k) % kSpectrumHistory].load(
                          std::memory_order_relaxed) * window[k];
        }
        sampleRate = static_cast<float>(input->stream->getSampleRate());
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
    result[0] = sampleRate;
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

extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_cancelTestWaiters(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) {
        engine->signal->testCancelled.store(true);
        engine->signal->wakeSpace();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_awaitTestSpace(JNIEnv *env, jclass, jint deviceId) {
    std::shared_ptr<EngineSignal> signal;
    {
        std::lock_guard<std::mutex> lock(controlMutex);
        if (!engine) return toJString(env, "");
        for (auto &e : engine->endpoints) if (!e->input && e->deviceId == deviceId) {
            signal = engine->signal;
            if (signal->closed.load() || signal->testCancelled.load() || signal->error.load()) break;
            signal->spaceWaiting.store(true);
            // Arm before checking the ring so a just-completed callback cannot
            // strand the writer. No endpoint pointer escapes the control lock.
            if (e->testRing.available() < kRingCapacity) {
                signal->spaceWaiting.store(false);
                return toJString(env, "");
            }
            break;
        }
    }
    if (!signal || signal->closed.load() || signal->testCancelled.load()) return toJString(env, "");
    if (!signal->error.load()) {
        pollfd fd{signal->spaceFd, POLLIN, 0};
        int result;
        do { result = ::poll(&fd, 1, 5000); } while (result < 0 && errno == EINTR);
        signal->spaceWaiting.store(false);
        if (result == 0) signal->fail(oboe::Result::ErrorTimeout);
        if (result < 0) return toJString(env, std::strerror(errno));
        uint64_t count;
        while (::read(signal->spaceFd, &count, sizeof(count)) < 0 && errno == EINTR) {}
    }
    const int code = signal->error.load();
    return toJString(env, code ? std::string(oboe::convertToText(static_cast<oboe::Result>(code)))
        + " (" + std::to_string(code) + ")" : "");
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
    int a[8] = {}; // endpoint spec width; monitoring never pins the buffer
    env->GetIntArrayRegion(config, 0, 7, a);
    const int deviceId = a[0], channels = a[2];
    if (deviceId <= 0 || channels < 1 || channels > kMaxChannels || a[1] < 0 || a[1] >= channels ||
        a[3] < 0 || a[3] > 384000 || a[4] < 0 || a[4] > 4 || a[5] < 0 || a[5] > 1) {
        return toJString(env, "ErrorIllegalArgument");
    }
    for (auto &e : engine->endpoints) {
        if (e->input && e->deviceId == deviceId) {
            if (e->channels >= channels && e->requestedRate == a[3] && e->requestedFormat == a[4] &&
                e->exclusive == a[5] && e->inputPreset == a[6] && e->requestedBufferBursts == a[7]) {
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
    // Reproduce a late Oboe error callback after its owning engine is gone.
    // Callback ownership must retain the endpoint and its independent signal.
    std::shared_ptr<Endpoint> lateCallback;
    {
        Engine old;
        old.endpoint(1, 1, true);
        lateCallback = old.endpoints.front();
    }
    lateCallback->onError(nullptr, oboe::Result::ErrorDisconnected);
    if (lateCallback->signal->error.load() != static_cast<int>(oboe::Result::ErrorDisconnected)) {
        return toJString(env, "FAIL late error callback lifetime");
    }
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
    // Block publication, channel extraction, additive mixing and counter wrap.
    InputGuard guard;
    guard.prepare(192, 4096, 768, 960, 1000);
    if (guard.frames != 0 || !guard.ready(80, 192) || guard.discard(4096, 192, 16384) != 3904)
        return toJString(env, "FAIL zero guard/partial startup/backlog");
    guard.completed(192, 80, 1000);
    if (guard.frames != 240 || guard.ready(400, 192) || !guard.ready(432, 192))
        return toJString(env, "FAIL quarter-burst reprime");
    for (int n = 0; n < 53; ++n) guard.completed(192, 192, 1000);
    guard.prepare(192, 4096, 768, 960, 1000);
    if (guard.frames != 0 || !guard.probing) return toJString(env, "FAIL guard recovery to zero");
    guard.completed(192, 180, 1000);
    if (guard.frames != 240 || guard.failedProbes != 1 || guard.cooldownSeconds != 30)
        return toJString(env, "FAIL guard rollback/cooldown");
    for (int n = 0; n < 53; ++n) guard.completed(192, 192, 1000);
    guard.prepare(192, 4096, 768, 960, 1000);
    if (guard.frames != 240 || guard.probing) return toJString(env, "FAIL premature guard retry");
    for (int n = 0; n < 700; ++n) {
        guard.completed(192, 192, 1000);
        guard.prepare(192, 4096, 768, 960, 1000);
    }
    if (guard.frames != 240 || guard.probing || guard.probes != 1)
        return toJString(env, "FAIL retrying failed guard floor");
    guard.prepare(192, 256, 768, 960, 1000);
    guard.completed(192, 0, 1000);
    if (guard.frames != 64 || !guard.ready(256, 192)) return toJString(env, "FAIL capacity-limited guard");
    guard.prepare(192, 96, 768, 960, 1000);
    if (guard.frames != 0 || !guard.ready(96, 192)) return toJString(env, "FAIL capacity below callback");
    // Zero guard really removes stale waveform samples, not just a statistic.
    Ring newest;
    for (int i = 0; i < 1024; ++i) newest.push(static_cast<float>(i));
    newest.trim(192 + guard.frames);
    if (!newest.pop(x) || x != 832.f) return toJString(env, "FAIL waveform age after trim");
    OutputProbe probe;
    probe.floor = 192;
    for (int n = 0; n < 78; ++n) {
        if (probe.update(576, 192, 1000, 192, false, false) != 0)
            return toJString(env, "FAIL early output reduction");
    }
    if (probe.update(576, 192, 1000, 192, false, false) != 384 ||
        probe.update(384, 192, 1000, 192, true, false) != -576 || probe.cooldownSeconds != 30)
        return toJString(env, "FAIL output gradual reset/rollback");
    for (int n = 0; n < 78; ++n) {
        if (probe.update(576, 192, 1000, 192, false, false) != 0)
            return toJString(env, "FAIL output cooldown");
    }
    for (int n = 0; n < 700; ++n) {
        if (probe.update(576, 192, 1000, 192, false, false) != 0)
            return toJString(env, "FAIL retrying failed output floor");
    }
    if (probe.update(576, 192, 1000, 192, false, true) != 0 || probe.stable != 0)
        return toJString(env, "FAIL capture gap stability reset");
    if (captureFramesToDiscard(1152, 192, 768, 960) != 0 ||
        captureFramesToDiscard(4096, 192, 768, 960) != 2944 ||
        captureFramesToDiscard(384, 192, 192, 192) != 0 ||
        captureFramesToDiscard(2112, 192, 768, 960, 2) != 0) {
        return toJString(env, "FAIL mismatched driver periods/backlog");
    }
    Ring blocks;
    blocks.write.store(UINT32_MAX - 127);
    blocks.read.store(UINT32_MAX - 127);
    std::array<float, 384 * 3> source;
    std::array<float, 384 * 2> mixed;
    for (int f = 0; f < 384; ++f) {
        source[f * 3] = -1.f;
        source[f * 3 + 1] = static_cast<float>(f);
        source[f * 3 + 2] = 1.f;
    }
    for (int n = 0; n < 1000; ++n) {
        mixed.fill(.5f);
        if (blocks.pushChannel(source.data(), 384, 3, 1) != 384 ||
            blocks.mix(mixed.data(), 384, 2, 1) != 384) return toJString(env, "FAIL block wrap/count");
        for (int f = 0; f < 384; ++f) {
            if (mixed[f * 2] != .5f || mixed[f * 2 + 1] != f + .5f) return toJString(env, "FAIL block channels/mix");
        }
    }
    // Actual concurrent SPSC block traffic, without relying on scheduling order.
    Ring concurrent;
    std::atomic<bool> bad{false};
    std::thread producer([&] {
        std::array<float, 64> block;
        for (int offset = 0; offset < 65536;) {
            for (int i = 0; i < 64; ++i) block[i] = static_cast<float>(offset + i);
            offset += concurrent.pushChannel(block.data(), 64, 1, 0);
        }
    });
    for (int offset = 0; offset < 65536;) {
        std::array<float, 64> block{};
        const int count = concurrent.mix(block.data(), 64, 1, 0);
        for (int i = 0; i < count; ++i) if (block[i] != offset + i) bad.store(true);
        offset += count;
    }
    producer.join();
    if (bad.load()) return toJString(env, "FAIL concurrent block order");
    // Multiple channels on one output bypass rings; cross-output fanout does not.
    Engine topology;
    topology.latencyMode = 0;
    auto *single = topology.endpoint(10, 2, true);
    auto *fanout = topology.endpoint(11, 1, true);
    auto *out1 = topology.endpoint(20, 2, false);
    auto *out2 = topology.endpoint(21, 1, false);
    topology.routes.push_back(std::make_unique<Route>(single, out1, 0, 0));
    topology.routes.push_back(std::make_unique<Route>(single, out1, 1, 1));
    topology.routes.push_back(std::make_unique<Route>(fanout, out1, 0, 0));
    topology.routes.push_back(std::make_unique<Route>(fanout, out2, 0, 0));
    topology.prepareRoutes();
    if (single->pullOwner != out1 || fanout->pullOwner || out1->pulledInputs.size() != 1 ||
        topology.routes[0]->ring || topology.routes[1]->ring ||
        !topology.routes[2]->ring || !topology.routes[3]->ring) return toJString(env, "FAIL transfer topology");
    auto &route = *topology.routes[2];
    std::array<float, 16> mono;
    mono.fill(.25f);
    std::array<float, 32> output{};
    route.ring->pushChannel(mono.data(), 16, 1, 0);
    if (route.mix(output.data(), 16, 16, 1000) != 16) return toJString(env, "FAIL extra startup queue");
    output.fill(0);
    if (route.mix(output.data(), 16, 16, 1000) != 0 || route.cushion.load() != 1) {
        return toJString(env, "FAIL underflow adaptation");
    }
    route.ring->pushChannel(mono.data(), 16, 1, 0);
    route.ring->pushChannel(mono.data(), 16, 1, 0);
    for (int i = 0; i < 630; ++i) {
        output.fill(0);
        if (route.mix(output.data(), 16, 16, 1000) != 16) return toJString(env, "FAIL route recovery");
        route.ring->pushChannel(mono.data(), 16, 1, 0);
    }
    if (route.cushion.load() != 0 || route.underflows.load() != 1) return toJString(env, "FAIL persistent cushion");
    while (route.ring->pushChannel(mono.data(), 16, 1, 0) > 0) {}
    output.fill(0);
    if (route.mix(output.data(), 16, 16, 1000) != 16 || route.ring->available() > 16) {
        return toJString(env, "FAIL stall backlog trim");
    }
    if (topology.routes[3]->ring->available() != 0) return toJString(env, "FAIL fanout block isolation");
    // The new fanout path starts without an extra burst, consumes a partial
    // waveform, and only this consumer trims its own old samples.
    route.minimumLatency = true;
    route.ring->trim(0);
    route.ring->pushChannel(mono.data(), 8, 1, 0);
    output.fill(0);
    if (route.mix(output.data(), 16, 16, 1000) != 8 || route.guardFrames.load() != 4 || output[0] != .25f)
        return toJString(env, "FAIL minimum fanout partial/adaptation");
    route.ring->pushChannel(mono.data(), 16, 1, 0);
    route.ring->pushChannel(mono.data(), 16, 1, 0);
    output.fill(0);
    if (route.mix(output.data(), 16, 16, 1000) != 16 || route.ring->available() != 4 || route.trimmed.load() != 12 ||
        topology.routes[3]->ring->available() != 0)
        return toJString(env, "FAIL minimum fanout trim/isolation");
    return toJString(env, "PASS native FIFO, PCM16/24/32/Float, callback lifetime, block SPSC/wrap/mix, direct/fanout topology, zero/quarter guard, capacity clamp, waveform trim, probe rollback/cooldown and stall recovery");
}

// Low-latency forwarding engine on top of Oboe / AAudio.
//
// Design constraints:
// - One fixed minimum-latency policy; no selectable forwarding modes.
// - The audio callback performs no allocation, no locking, no JNI and no disk I/O.
//   Errors/settings and a waiting ringtone writer use coalesced nonblocking eventfd wakes.
// - Each physical device gets exactly one multi-channel stream; routes share it.
// - Each input with one output device is read directly by that output callback.
//   Only cross-output fanout uses SPSC rings, published/consumed once per block.
// - Paired streams must negotiate identical rate and format; samples are mapped
//   one-to-one in the stream's own raw domain. The engine never converts between
//   PCM formats, so a pair without common parameters fails instead of resampling.
// - Inputs keep zero scheduling guard: a short read leaves its tail silent and is
//   never compensated by re-accumulating a queue (doc sections 7/8).

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
#include <dlfcn.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <cerrno>
#include <system_error>
#include <poll.h>
#include <android/log.h>

namespace {

constexpr uint32_t kRingCapacity = 16384;
constexpr uint32_t kSpectrumHistory = 1024;
constexpr int kMaxChannels = 32;
constexpr int kMaxRoutes = 128;
// Upper bound for the pinned output buffer, in bursts (0 keeps adaptive tuning).
constexpr int kMaxBufferBursts = 32;
constexpr int kSpectrumFft = 512;
// One effect block handed to a processor; larger callbacks are split in place.
constexpr int32_t kMaxBlockFrames = 4096;
// AAudio MMAP policy values (doc section 7): 0 keeps the system policy untouched.
constexpr const char *kMmapPolicyNames[] = {"System", "Never", "Auto"};

bool validPreset(int value) {
    return value == 0 || value == 1 || value == 5 || value == 6 || value == 7 ||
           value == 9 || value == 10;
}

// ---------------------------------------------------------------------------
// Raw sample words. One sample travels as one 32-bit word in its own stream
// format domain: sign-extended integer for I16/I24/I32, the raw bit pattern for
// Float. Forwarding only ever moves and sums words of one identical negotiated
// format, so a sample reaches the output bit-exact unless mixing saturates.
// ---------------------------------------------------------------------------

int32_t loadWord(const void *data, int index, oboe::AudioFormat format) {
    switch (format) {
    case oboe::AudioFormat::I16:
        return static_cast<const int16_t *>(data)[index];
    case oboe::AudioFormat::I32:
        return static_cast<const int32_t *>(data)[index];
    case oboe::AudioFormat::Float: {
        int32_t bits;
        std::memcpy(&bits, static_cast<const float *>(data) + index, sizeof(bits));
        return bits;
    }
    case oboe::AudioFormat::I24: {
        const auto *p = static_cast<const uint8_t *>(data) + static_cast<size_t>(index) * 3;
        int32_t value = p[0] | (p[1] << 8) | (p[2] << 16);
        if (value & 0x800000) value |= static_cast<int32_t>(0xff000000);
        return value;
    }
    default:
        return 0;
    }
}

void storeWord(void *data, int index, int32_t word, oboe::AudioFormat format) {
    switch (format) {
    case oboe::AudioFormat::I16:
        static_cast<int16_t *>(data)[index] = static_cast<int16_t>(std::clamp(word, -32768, 32767));
        break;
    case oboe::AudioFormat::I32:
        static_cast<int32_t *>(data)[index] = word;
        break;
    case oboe::AudioFormat::Float:
        std::memcpy(static_cast<float *>(data) + index, &word, sizeof(word));
        break;
    case oboe::AudioFormat::I24: {
        const int32_t value = std::clamp(word, -8388608, 8388607);
        auto *p = static_cast<uint8_t *>(data) + static_cast<size_t>(index) * 3;
        p[0] = static_cast<uint8_t>(value);
        p[1] = static_cast<uint8_t>(value >> 8);
        p[2] = static_cast<uint8_t>(value >> 16);
        break;
    }
    default:
        break;
    }
}

// Saturating sum of one word into one destination sample (doc section 8: hard
// clipping, no limiter).
void addWord(void *data, int index, int32_t word, oboe::AudioFormat format) {
    switch (format) {
    case oboe::AudioFormat::I16: {
        const int32_t sum = static_cast<const int16_t *>(data)[index] + word;
        static_cast<int16_t *>(data)[index] = static_cast<int16_t>(std::clamp(sum, -32768, 32767));
        break;
    }
    case oboe::AudioFormat::I32: {
        const int64_t sum = static_cast<int64_t>(static_cast<const int32_t *>(data)[index]) + word;
        static_cast<int32_t *>(data)[index] =
            static_cast<int32_t>(std::clamp<int64_t>(sum, INT32_MIN, INT32_MAX));
        break;
    }
    case oboe::AudioFormat::Float: {
        float left, right;
        std::memcpy(&left, static_cast<const float *>(data) + index, sizeof(left));
        std::memcpy(&right, &word, sizeof(right));
        const float sum = std::clamp(left + right, -1.f, 1.f);
        std::memcpy(static_cast<float *>(data) + index, &sum, sizeof(sum));
        break;
    }
    case oboe::AudioFormat::I24:
        storeWord(data, index, loadWord(data, index, format) + word, format);
        break;
    default:
        break;
    }
}

// Adds one source channel onto one destination channel; both endpoints of a
// route share the same negotiated format (validated before start).
void addChannelMapped(void *dst, oboe::AudioFormat format, int dstChannels, int dstChannel,
                      const void *src, int srcChannels, int srcChannel, int frames) {
    for (int f = 0; f < frames; ++f) {
        addWord(dst, f * dstChannels + dstChannel,
                loadWord(src, f * srcChannels + srcChannel, format), format);
    }
}

// Java float -> stream-domain word. Control-thread diagnostics only (ringtone
// test writer); the forwarding path never converts formats.
int32_t packFloatWord(float value, oboe::AudioFormat format) {
    const double v = std::clamp(static_cast<double>(value), -1.0, 1.0);
    switch (format) {
    case oboe::AudioFormat::Float: {
        const float f = static_cast<float>(v);
        int32_t bits;
        std::memcpy(&bits, &f, sizeof(bits));
        return bits;
    }
    case oboe::AudioFormat::I16:
        return static_cast<int32_t>(std::clamp(v * 32768.0, -32768.0, 32767.0));
    case oboe::AudioFormat::I32:
        return static_cast<int32_t>(std::clamp(v * 2147483648.0, -2147483648.0, 2147483647.0));
    case oboe::AudioFormat::I24:
        return static_cast<int32_t>(std::clamp(v * 8388608.0, -8388608.0, 8388607.0));
    default:
        return 0;
    }
}

// Stream sample -> float for the diagnostic spectrum tap only.
float readSampleFloat(const void *data, int index, oboe::AudioFormat format) {
    if (format == oboe::AudioFormat::Float) return static_cast<const float *>(data)[index];
    if (format == oboe::AudioFormat::I16) return static_cast<const int16_t *>(data)[index] / 32768.f;
    if (format == oboe::AudioFormat::I32) return static_cast<const int32_t *>(data)[index] / 2147483648.f;
    if (format == oboe::AudioFormat::I24) return loadWord(data, index, format) / 8388608.f;
    return 0.f;
}

// ---------------------------------------------------------------------------
// Replaceable effect slot (doc section 9). Reserved for future DSP; forwarding
// calls processInPlace on raw captured PCM in bounded blocks. Replacement or
// parameter changes happen on the control thread while streams are stopped.
// ---------------------------------------------------------------------------

struct PcmFormat {
    int32_t sampleRate = 0;
    int32_t channels = 0;
    oboe::AudioFormat format = oboe::AudioFormat::Unspecified;
};

class RealtimeProcessor {
public:
    virtual ~RealtimeProcessor() = default;
    virtual bool prepare(PcmFormat format, int32_t maxBlockFrames) = 0;
    virtual void processInPlace(void *pcm, int32_t frames, oboe::AudioFormat format) noexcept = 0;
    virtual int32_t latencyFrames() const noexcept = 0;
    virtual void reset() noexcept = 0;
    virtual const char *name() const noexcept = 0;
};

// Initial processor: samples pass through untouched with zero added latency.
class BypassProcessor final : public RealtimeProcessor {
public:
    bool prepare(PcmFormat format, int32_t maxBlockFrames) override {
        if (format.sampleRate <= 0 || format.channels <= 0 || maxBlockFrames <= 0) return false;
        configured = format;
        maxBlock = maxBlockFrames;
        return true;
    }
    void processInPlace(void *, int32_t, oboe::AudioFormat) noexcept override {}
    int32_t latencyFrames() const noexcept override { return 0; }
    void reset() noexcept override {}
    const char *name() const noexcept override { return "Bypass"; }

private:
    PcmFormat configured;
    int32_t maxBlock = 0;
};

// ---------------------------------------------------------------------------
// AAudio MMAP process-policy control (doc section 7). The three entry points
// are runtime-detected test extensions (public since API 36); older devices
// report Unavailable and opens proceed with the untouched system policy.
// ---------------------------------------------------------------------------

struct MMapControl {
    using SetPolicy = int32_t (*)(int32_t);
    using GetPolicy = int32_t (*)();
    using IsUsed = bool (*)(void *);
    SetPolicy set = nullptr;
    GetPolicy get = nullptr;
    IsUsed used = nullptr;
    bool available = false;

    MMapControl() {
        void *lib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return;
        set = reinterpret_cast<SetPolicy>(dlsym(lib, "AAudio_setMMapPolicy"));
        get = reinterpret_cast<GetPolicy>(dlsym(lib, "AAudio_getMMapPolicy"));
        used = reinterpret_cast<IsUsed>(dlsym(lib, "AAudioStream_isMMapUsed"));
        available = set != nullptr && get != nullptr;
    }
};

MMapControl &mmapControl() {
    static MMapControl control; // intentionally alive for the whole process
    return control;
}

// Single-producer single-consumer wait-free ring of raw-format sample words.
struct Ring {
    std::array<int32_t, kRingCapacity> data{};
    std::atomic<uint32_t> write{0};
    std::atomic<uint32_t> read{0};

    bool push(int32_t value) {
        const auto w = write.load(std::memory_order_relaxed);
        if (w - read.load(std::memory_order_acquire) >= kRingCapacity) return false;
        data[w % kRingCapacity] = value;
        write.store(w + 1, std::memory_order_release);
        return true;
    }

    bool pop(int32_t &value) {
        const auto r = read.load(std::memory_order_relaxed);
        if (r == write.load(std::memory_order_acquire)) return false;
        value = data[r % kRingCapacity];
        read.store(r + 1, std::memory_order_release);
        return true;
    }

    // One publication per block instead of per sample. Only the consumer owns read.
    uint32_t pushChannel(const void *src, uint32_t frames, oboe::AudioFormat format,
                         int channels, int channel) {
        const auto w = write.load(std::memory_order_relaxed);
        const auto space = kRingCapacity - (w - read.load(std::memory_order_acquire));
        const auto count = std::min(frames, space);
        for (uint32_t f = 0; f < count; ++f) {
            data[(w + f) % kRingCapacity] =
                loadWord(src, static_cast<int>(f * channels + channel), format);
        }
        write.store(w + count, std::memory_order_release);
        return count;
    }

    uint32_t mix(void *dst, uint32_t frames, oboe::AudioFormat format, int channels, int channel) {
        const auto r = read.load(std::memory_order_relaxed);
        const auto count = std::min(frames, write.load(std::memory_order_acquire) - r);
        for (uint32_t f = 0; f < count; ++f) {
            addWord(dst, static_cast<int>(f * channels + channel),
                    data[(r + f) % kRingCapacity], format);
        }
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

// Error threads can outlive Engine shutdown. Never let them dereference Engine.
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
    // Raw capture in the stream's own format; sized for the largest word (4B).
    std::vector<uint8_t> capture;
    std::unique_ptr<RealtimeProcessor> processor;
    // Actual AAudioStream_isMMapUsed result: 1 yes, 0 no, -1 unknown/unavailable.
    int mmapUsed = -1;
    Endpoint *pullOwner = nullptr;
    // Fixed before start; callbacks never traverse a control-thread-mutated vector.
    std::vector<Endpoint *> pulledInputs;
    std::vector<Route *> inputRoutes;
    std::vector<Route *> outputRoutes;
    std::atomic<uint64_t> dropped{0};
    std::atomic<int32_t> queued{0};
    std::atomic<int32_t> callbackFrames{0};
    std::atomic<uint32_t> shortReads{0};
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
    OutputProbe outputProbe;
    std::atomic<int> outputMinimum{0}, outputCooldown{15};
    std::atomic<uint32_t> outputReductions{0}, outputFailures{0};
    uint64_t lastFrames = 0;
    std::string configSnapshot;
    std::array<int, 2> publishedSettings{{-1, -1}}; // callback-owned
    void publishSettings() {
        const std::array<int, 2> current{{stream->getBufferSizeInFrames(), outputMinimum.load()}};
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
    bool tapHistory(const void *buffer, int numFrames);
    void processCaptured(void *pcm, int32_t frames);
    int pull(int numFrames);
};

// One forwarding pair. A private ring is needed only for cross-output fanout.
struct Route {
    Endpoint *in;
    Endpoint *out;
    int inChannel;
    int outChannel;
    std::unique_ptr<Ring> ring;
    std::atomic<uint64_t> underflows{0};
    std::atomic<uint64_t> trimmed{0};

    Route(Endpoint *i, Endpoint *o, int ic, int oc) : in(i), out(o), inChannel(ic), outChannel(oc) {}

    // Zero-margin minimum latency: keep at most what this callback consumes,
    // mix what is available, and leave a short block's tail silent. No guard
    // frames and no re-accumulation threshold.
    uint32_t mix(void *dst, uint32_t numFrames, oboe::AudioFormat format) {
        trimmed.fetch_add(ring->trim(numFrames), std::memory_order_relaxed);
        const auto count = ring->mix(dst, numFrames, format, out->channels, outChannel);
        if (count < numFrames) underflows.fetch_add(1, std::memory_order_relaxed);
        return count;
    }
};

struct Engine {
#ifndef NDEBUG
    bool freezeProgressForTest = false; // serial control thread only
#endif
    // Requested AAudio MMAP policy: 0 System (untouched), 1 Never, 2 Auto.
    int mmapPolicy = 0;
    enum ControlState { ControlUnused = 0, ControlApplied, ControlUnavailable, ControlFailed };
    ControlState mmapControlState = ControlUnused;
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

    // Automatic outputs inherit the routed inputs' actual parameters (doc
    // section 5) so an auto/auto pair negotiates identical streams and needs
    // no application-level conversion.
    void inheritInputActuals() {
        for (auto &e : endpoints) {
            if (e->input) continue;
            for (auto *r : e->outputRoutes) {
                if (!r->in->stream) continue;
                if (e->requestedRate == 0) e->requestedRate = r->in->stream->getSampleRate();
                if (e->requestedFormat == 0) {
                    e->requestedFormat = static_cast<int>(r->in->stream->getFormat());
                }
            }
        }
    }

    // Stops every stream that has been started. Streams which refuse to stop
    // may still own a running callback; the caller must then keep the objects
    // alive instead of closing them (doc section 6).
    oboe::Result stopStreams() {
        active.store(false);
        signal->finish();
        oboe::Result first = oboe::Result::OK;
        for (auto &e : endpoints) {
            if (!e->stream) continue;
            const auto state = e->stream->getState();
            if (state == oboe::StreamState::Open || state == oboe::StreamState::Uninitialized ||
                state == oboe::StreamState::Closing || state == oboe::StreamState::Closed) {
                continue; // never started: no callback can reference it anymore
            }
            const auto result = e->stream->stop(1000000000);
            // A disconnected or already-closed stream is quiescent by definition.
            if (result != oboe::Result::OK && result != oboe::Result::ErrorDisconnected &&
                result != oboe::Result::ErrorClosed && first == oboe::Result::OK) {
                first = result;
            }
        }
        return first;
    }

    void closeStreams() {
        for (auto &e : endpoints) {
            if (e->stream) e->stream->close();
        }
        // A callback already executing on another endpoint can still reference
        // a route's input/output stream. Keep all stream objects until ALL close.
        for (auto &e : endpoints) e->stream.reset();
    }

    ~Engine() {
        // Defensive last resort; the regular path stops/closes via shutdownEngine.
        stopStreams();
        closeStreams();
    }

    std::string open(Endpoint &e) {
        oboe::AudioStreamBuilder builder;
        builder.setAudioApi(oboe::AudioApi::AAudio)
            ->setDirection(e.input ? oboe::Direction::Input : oboe::Direction::Output)
            ->setDeviceId(e.deviceId)
            ->setChannelCount(e.channels)
            ->setFormat(static_cast<oboe::AudioFormat>(e.requestedFormat))
            ->setSampleRate(e.requestedRate)
            ->setFormatConversionAllowed(false)
            ->setChannelConversionAllowed(false)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(e.exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setSessionId(oboe::SessionId::None)
            ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(e.shared_from_this()));
        if (e.input) {
            // Automatic mode prefers Generic; an explicit choice stays strict.
            builder.setInputPreset(e.inputPreset == 0 ? oboe::InputPreset::Generic
                                                      : static_cast<oboe::InputPreset>(e.inputPreset));
        } else {
            // Doc section 5: media playback attributes on the output side.
            builder.setUsage(oboe::Usage::Media)->setContentType(oboe::ContentType::Music);
        }
        if (!e.pullOwner) {
            builder.setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(e.shared_from_this()));
        }
        // No buffer-capacity request; the backend negotiates it (doc section 5).

        // Doc section 7: guard every actual open with the requested MMAP policy
        // and always restore the previous process policy afterwards.
        auto &control = mmapControl();
        int32_t savedPolicy = 0;
        bool policyApplied = false;
        if (mmapPolicy != 0) {
            if (!control.available) {
                mmapControlState = ControlUnavailable;
            } else {
                savedPolicy = control.get(); // save before modifying (doc 7.3)
                if (control.set(mmapPolicy) == 0) {
                    policyApplied = true;
                    mmapControlState = ControlApplied;
                } else {
                    mmapControlState = ControlFailed;
                }
            }
        }
        oboe::Result result = builder.openStream(e.stream);
        if (result != oboe::Result::OK && e.input && e.inputPreset == 0) {
            // Some vendor policies reject Generic. Automatic mode can use the
            // speech-recognition path; explicit choices stay strict.
            if (e.stream) e.stream->close();
            e.stream.reset();
            builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
            result = builder.openStream(e.stream);
        }
        if (policyApplied) control.set(savedPolicy);
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
        if (e.requestedFormat && actual != static_cast<oboe::AudioFormat>(e.requestedFormat)) {
            return "AAudio requested format=" + std::to_string(e.requestedFormat) +
                   ", actual=" + oboe::convertToText(actual);
        }
        // Exclusive/LowLatency are requests; a system downgrade is recorded in
        // the actual parameters instead of failing the open (doc section 5).

        // Real MMAP usage of this stream, when the extension is queryable.
        if (control.used) {
            if (void *underlying = e.stream->getUnderlyingStream()) {
                e.mmapUsed = control.used(underlying) ? 1 : 0;
            }
        }

        if (e.input) {
            if (e.pullOwner) {
                // Doc section 5: two bursts of size are scheduling headroom for
                // the read-driven chain, not a queued-audio target. Failures
                // keep the backend default and stay visible in the statistics.
                e.stream->setBufferSizeInFrames(e.stream->getFramesPerBurst() * 2);
                e.capture.resize(static_cast<size_t>(kRingCapacity) * e.channels * 4);
            }
            e.processor = std::make_unique<BypassProcessor>();
            const PcmFormat format{e.stream->getSampleRate(), e.stream->getChannelCount(),
                                   e.stream->getFormat()};
            if (!e.processor->prepare(format, kMaxBlockFrames)) {
                return "RealtimeProcessor prepare failed";
            }
            e.history.clear();
            e.historyWrite.store(0);
            for (int c = 0; c < e.channels; ++c) {
                auto buffer = std::make_unique<std::array<std::atomic<float>, kSpectrumHistory>>();
                for (auto &v : *buffer) v.store(0);
                e.history.push_back(std::move(buffer));
            }
        } else {
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
                    (e.outputRoutes.empty() ? 2 : 1);
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
        return "";
    }
};

bool Endpoint::onError(oboe::AudioStream *, oboe::Result result) {
    // Handled from the control thread; do not close, lock or allocate here.
    signal->fail(result);
    return true;
}

// Diagnostic tap (doc RecorderTap analogue): converts only the most recent
// spectrum window to float. The forwarding path itself stays in the raw
// stream format and never converts.
bool Endpoint::tapHistory(const void *buffer, int numFrames) {
    const auto format = stream->getFormat();
    if (format == oboe::AudioFormat::Float) {
        const auto *values = static_cast<const float *>(buffer);
        for (int k = 0; k < numFrames * channels; ++k) {
            if (!std::isfinite(values[k])) {
                signal->fail(oboe::Result::ErrorInvalidFormat);
                return false;
            }
        }
    }
    const auto position = historyWrite.load(std::memory_order_relaxed);
    // A large input block only needs its most recent spectrum window copied.
    for (int f = std::max(0, numFrames - static_cast<int>(kSpectrumHistory)); f < numFrames; ++f) {
        for (int c = 0; c < channels; ++c) {
            (*history[c])[(position + f) % kSpectrumHistory].store(
                readSampleFloat(buffer, f * channels + c, format), std::memory_order_relaxed);
        }
    }
    historyWrite.store(position + numFrames, std::memory_order_release);
    frames.fetch_add(numFrames, std::memory_order_relaxed);
    return true;
}

// Runs the reserved effect slot in bounded blocks, in place on raw PCM.
void Endpoint::processCaptured(void *pcm, int32_t frames) {
    if (!processor || frames <= 0) return;
    const auto format = stream->getFormat();
    const int32_t frameBytes = channels * stream->getBytesPerSample();
    for (int32_t done = 0; done < frames; ) {
        const int32_t block = std::min(kMaxBlockFrames, frames - done);
        processor->processInPlace(static_cast<uint8_t *>(pcm) + static_cast<size_t>(done) * frameBytes,
                                  block, format);
        done += block;
    }
}

// Read-driven capture for the direct chain (doc section 8): one nonblocking
// read; a short read leaves the unfilled output tail silent. No guard frames,
// no re-accumulation threshold, no backlog discard.
int Endpoint::pull(int numFrames) {
    callbackFrames.store(numFrames, std::memory_order_relaxed);
    // Startup window: the output runs first and stays silent until the input
    // actually reached Started. The audio thread never waits for it.
    if (stream->getState() != oboe::StreamState::Started) return 0;
    if (stream->getDeviceId() != deviceId) {
        signal->fail(oboe::Result::ErrorDisconnected);
        return 0;
    }
    auto read = stream->read(capture.data(), numFrames, 0); // Never block a real-time callback.
    if (!read) {
        signal->fail(read.error());
        return 0;
    }
    const int count = read.value();
    if (count < numFrames) {
        shortReads.fetch_add(1, std::memory_order_relaxed);
        gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
    }
    const auto available = stream->getAvailableFrames();
    if (available) queued.store(available.value(), std::memory_order_relaxed);
    if (count > 0) {
        if (!tapHistory(capture.data(), count)) return 0;
        processCaptured(capture.data(), count);
    }
    return count;
}

oboe::DataCallbackResult Endpoint::onAudioReady(oboe::AudioStream *s, void *buffer, int32_t numFrames) {
    callbackFrames.store(numFrames, std::memory_order_relaxed);
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
        // Fanout producer or monitoring preview: tap, run the effect slot in
        // place, then publish raw words once per block.
        if (!tapHistory(buffer, numFrames)) return oboe::DataCallbackResult::Stop;
        processCaptured(buffer, numFrames);
        for (auto *r : inputRoutes) {
            const auto count = r->ring->pushChannel(buffer, numFrames, s->getFormat(), channels, r->inChannel);
            dropped.fetch_add(numFrames - count, std::memory_order_relaxed);
        }
    } else {
        const auto gapsBefore = gaps.load(std::memory_order_relaxed);
        std::memset(buffer, 0, static_cast<size_t>(numFrames) * channels * s->getBytesPerSample());
        testRing.mix(buffer, numFrames, s->getFormat(), channels, testChannel.load(std::memory_order_relaxed));
        if (outputRoutes.empty()) signal->wakeSpace(); // only a waiting ringtone writer
        for (auto *in : pulledInputs) {
            const int count = in->pull(numFrames);
            for (auto *r : in->inputRoutes) {
                addChannelMapped(buffer, s->getFormat(), channels, r->outChannel,
                                 in->capture.data(), in->channels, r->inChannel, count);
            }
            gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
        }
        for (auto *r : outputRoutes) {
            if (!r->ring) continue;
            const auto count = r->mix(buffer, numFrames, s->getFormat());
            gaps.fetch_add(numFrames - count, std::memory_order_relaxed);
        }
        const auto count = s->getXRunCount();
        if (count) xruns.store(count.value(), std::memory_order_relaxed);
        if (tuner) {
            const auto result = tuner->tune();
            if (result != oboe::Result::OK && result != oboe::Result::ErrorUnimplemented) signal->fail(result);
            if (count) {
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
            }
        }
        frames.fetch_add(numFrames, std::memory_order_relaxed);
    }
    publishSettings();
    return signal->active.load() ? oboe::DataCallbackResult::Continue : oboe::DataCallbackResult::Stop;
}

std::mutex controlMutex;
std::unique_ptr<Engine> engine;

// Stop-failure quarantine (doc section 6): streams which refused to stop may
// still own running callbacks, so their engine is kept alive instead of being
// closed and destroyed. Deliberately never freed before process exit.
std::vector<std::unique_ptr<Engine>> &retiredEngines() {
    static auto *list = new std::vector<std::unique_ptr<Engine>>();
    return *list;
}

// Stops and releases the engine on success; reports and retains it otherwise.
void shutdownEngine() {
    if (!engine) return;
    const auto result = engine->stopStreams();
    if (result == oboe::Result::OK) {
        engine->closeStreams();
        engine.reset();
        return;
    }
    __android_log_print(ANDROID_LOG_ERROR, "IEM-engine",
                        "AAudio stop failed: %s (%d); retaining stream objects",
                        oboe::convertToText(result), static_cast<int>(result));
    retiredEngines().push_back(std::move(engine));
}

jstring toJString(JNIEnv *env, const std::string &text) { return env->NewStringUTF(text.c_str()); }

// Returns the raw error, if any, and tears the engine down before returning it.
std::string stopError() {
    if (!engine) return "";
    const auto code = engine->error.load();
    if (!code) return "";
    const std::string text = oboe::convertToText(static_cast<oboe::Result>(code));
    shutdownEngine();
    return text + " (" + std::to_string(code) + ")";
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_startNative(JNIEnv *env, jclass, jintArray spec, jint mmapPolicy) {
    std::lock_guard<std::mutex> lock(controlMutex);
    try {
        shutdownEngine();
        if (!spec || mmapPolicy < 0 || mmapPolicy > 2) return toJString(env, "ErrorIllegalArgument");
        const int length = env->GetArrayLength(spec);
        // 8 ints per endpoint: id, channel, channels, rate, format, exclusive,
        // preset, bufferBursts.
        if (length == 0 || length % 16 || length > 16 * kMaxRoutes) {
            return toJString(env, oboe::convertToText(oboe::Result::ErrorIllegalArgument));
        }
        std::vector<jint> a(length);
        env->GetIntArrayRegion(spec, 0, length, a.data());
        engine = std::make_unique<Engine>();
        engine->mmapPolicy = mmapPolicy;
        for (int k = 0; k < length; k += 16) {
            for (int side : {0, 8}) {
                const int x = k + side;
                if (a[x] != -1 && (a[x] <= 0 || a[x + 1] < 0 || a[x + 2] < 1 || a[x + 2] > kMaxChannels ||
                                   a[x + 1] >= a[x + 2] || a[x + 3] < 0 || a[x + 3] > 384000 ||
                                   a[x + 4] < 0 || a[x + 4] > 4 || a[x + 5] < 0 || a[x + 5] > 1 ||
                                   !validPreset(a[x + 6]) ||
                                   a[x + 7] < 0 || a[x + 7] > kMaxBufferBursts)) {
                    shutdownEngine();
                    return toJString(env, oboe::convertToText(oboe::Result::ErrorIllegalArgument));
                }
            }
            Endpoint *in = engine->configured(&a[k], true);
            Endpoint *out = engine->configured(&a[k + 8], false);
            if (in && out) {
                engine->routes.push_back(std::make_unique<Route>(in, out, a[k + 1], a[k + 9]));
            }
            if (!in && !out) {
                shutdownEngine();
                return toJString(env, "ErrorIllegalArgument");
            }
        }
        engine->prepareRoutes();
        // Doc section 6: inputs open before outputs so automatic outputs can
        // inherit the actual input parameters.
        for (auto &e : engine->endpoints) {
            if (!e->input) continue;
            const std::string error = engine->open(*e);
            if (!error.empty()) {
                shutdownEngine();
                return toJString(env, error);
            }
        }
        engine->inheritInputActuals();
        for (auto &e : engine->endpoints) {
            if (e->input) continue;
            const std::string error = engine->open(*e);
            if (!error.empty()) {
                shutdownEngine();
                return toJString(env, error);
            }
        }
        // No application-level conversion exists: a route whose negotiated
        // parameters differ has no common domain and fails instead (doc 5).
        for (auto &route : engine->routes) {
            const auto &in = route->in->stream;
            const auto &out = route->out->stream;
            if (in->getSampleRate() != out->getSampleRate()) {
                const std::string message =
                    "ErrorInvalidRate: AAudio input sampleRate=" +
                    std::to_string(in->getSampleRate()) +
                    ", output sampleRate=" + std::to_string(out->getSampleRate()) +
                    "; no system cross-stream sample-rate converter";
                shutdownEngine();
                return toJString(env, message);
            }
            if (in->getFormat() != out->getFormat()) {
                const std::string message =
                    std::string("ErrorInvalidFormat: AAudio input format=") +
                    oboe::convertToText(in->getFormat()) +
                    ", output format=" + oboe::convertToText(out->getFormat()) +
                    "; forwarding maps samples one-to-one without conversion";
                shutdownEngine();
                return toJString(env, message);
            }
        }
        engine->active.store(true);
        // Doc section 6: the output starts first (nonblocking); its callback
        // stays silent until the input reached Started. nativeStart only
        // reports success after the input startup actually completed.
        for (auto &e : engine->endpoints) {
            if (e->input) continue;
            const oboe::Result result = e->stream->requestStart();
            if (result != oboe::Result::OK) {
                shutdownEngine();
                return toJString(env, oboe::convertToText(result));
            }
        }
        for (auto &e : engine->endpoints) {
            if (!e->input) continue;
            const oboe::Result result = e->stream->start(2000000000LL); // wait for Started
            if (result != oboe::Result::OK) {
                shutdownEngine();
                return toJString(env, oboe::convertToText(result));
            }
        }
        // Doc section 2.2: re-verify the actual routing with both ends running.
        for (auto &e : engine->endpoints) {
            if (e->stream->getDeviceId() != e->deviceId) {
                std::ostringstream message;
                message << "AAudio routing: requested deviceId=" << e->deviceId
                        << ", returned deviceId=" << e->stream->getDeviceId();
                shutdownEngine();
                return toJString(env, message.str());
            }
        }
        return toJString(env, stopError());
    } catch (const std::exception &e) {
        shutdownEngine();
        return toJString(env, e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_stopNative(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(controlMutex);
    shutdownEngine();
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
                 << " · capacity=" << s->getBufferCapacityInFrames()
                 << "\ntransfer=" << (e.pullOwner ? "output-pull" : e.input ? "input-callback" : "output-callback");
            if (e.input) {
                text << " · preset=" << static_cast<int>(s->getInputPreset());
                if (e.processor) {
                    text << " · processor=" << e.processor->name()
                         << " latency=" << e.processor->latencyFrames();
                }
            }
            if (e.pullOwner) text << " · appQueue=0";
            text << "\nmmap=" << (e.mmapUsed > 0 ? "yes" : e.mmapUsed == 0 ? "no" : "unknown");
            if (engine->mmapPolicy != 0) {
                text << " · mmapPolicy=" << kMmapPolicyNames[engine->mmapPolicy];
                if (engine->mmapControlState == Engine::ControlUnavailable) {
                    text << " (control=Unavailable)";
                } else if (engine->mmapControlState == Engine::ControlFailed) {
                    text << " (control=Failed)";
                }
            }
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
        if (!e.input) text << " · outputTarget=" << e.outputMinimum.load();
    } else {
        auto count = s->getXRunCount();
        if (count) e.xruns.store(count.value());
        text << "frames=" << e.frames.load() << " · xrun=" << e.xruns.load()
             << " · gap=" << e.gaps.load() << " · dropped=" << e.dropped.load()
             << " · callback=" << e.callbackFrames.load();
        if (e.pullOwner) text << " · queued=" << e.queued.load()
            << " · shortReads=" << e.shortReads.load();
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
// Truthful verification: only an actually-granted Exclusive stream counts as
// support; a silent downgrade to Shared means "not granted" (doc section 2.2).
extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_NativeAudio_exclusiveSupport(JNIEnv *, jclass, jint deviceId, jboolean input, jint channels) {
    std::lock_guard<std::mutex> lock(controlMutex);
    if (engine) return -1;
    try {
        Engine temporary;
        Endpoint *e = temporary.endpoint(deviceId, channels, input);
        e->exclusive = 1;
        const std::string error = temporary.open(*e);
        if (!error.empty() || !e->stream) return 0;
        return e->stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1 : 0;
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
        ->setFormat(oboe::AudioFormat::Unspecified);
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
            ->setSessionId(oboe::SessionId::None)
            ->setInputPreset(oboe::InputPreset::Generic)
            ->setFormatConversionAllowed(false)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);
        if (!input) {
            builder.setUsage(oboe::Usage::Media)->setContentType(oboe::ContentType::Music);
        }
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
    if (!out || !out->stream || channel < 0 || channel >= out->channels) return -898;
    std::array<float, kRingCapacity> values{};
    env->GetFloatArrayRegion(mono, offset, frames, values.data());
    // Control-thread conversion into the endpoint's own raw domain (a
    // diagnostic path; forwarding itself never converts).
    const auto format = out->stream->getFormat();
    out->testChannel.store(channel);
    out->testDrainFrame = -1;
    int written = 0;
    for (; written < frames; ++written) {
        if (!out->testRing.push(packFloatWord(values[written], format))) break;
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
        a[3] < 0 || a[3] > 384000 || a[4] < 0 || a[4] > 4 || a[5] < 0 || a[5] > 1 ||
        !validPreset(a[6])) {
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
        const oboe::Result r = e->stream->start(2000000000LL); // wait for Started
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

// Verifies the raw sample-word pipeline and lock-free rings without audio devices.
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
    int32_t x = 0;
    if (ring.pop(x)) return toJString(env, "FAIL empty");
    for (uint32_t i = 0; i < kRingCapacity; ++i) {
        if (!ring.push(static_cast<int32_t>(i))) return toJString(env, "FAIL push");
    }
    if (ring.push(1)) return toJString(env, "FAIL overflow");
    for (uint32_t i = 0; i < kRingCapacity; ++i) {
        if (!ring.pop(x) || x != static_cast<int32_t>(i)) return toJString(env, "FAIL order");
    }
    for (int k = 0; k < 100000; ++k) {
        if (!ring.push(k) || !ring.pop(x) || x != k) {
            return toJString(env, "FAIL wrap");
        }
    }
    Ring left, right;
    for (int k = 0; k < 1024; ++k) {
        left.push(k);
        right.push(-k);
    }
    left.trim(128);
    if (left.available() != 128 || right.available() != 1024) return toJString(env, "FAIL fanout");
    left.pop(x);
    if (x != 896) return toJString(env, "FAIL trim");
    right.pop(x);
    right.pop(x);
    if (x != -1) return toJString(env, "FAIL isolation");
    // Raw word roundtrip through every supported stream format.
    alignas(8) uint8_t raw[64];
    const float values[] = {-1.f, -.5f, 0.f, .5f, .999f};
    for (auto format : {oboe::AudioFormat::I16, oboe::AudioFormat::I24, oboe::AudioFormat::I32,
                        oboe::AudioFormat::Float}) {
        for (int k = 0; k < 5; ++k) {
            storeWord(raw, k, packFloatWord(values[k], format), format);
        }
        for (int k = 0; k < 5; ++k) {
            if (std::abs(readSampleFloat(raw, k, format) - values[k]) > .00004f) {
                return toJString(env, "FAIL PCM word roundtrip");
            }
        }
    }
    // Saturating sums in every format: mixing hard-clips, it never wraps.
    alignas(8) uint8_t mixed[32];
    for (auto format : {oboe::AudioFormat::I16, oboe::AudioFormat::I24, oboe::AudioFormat::I32,
                        oboe::AudioFormat::Float}) {
        std::memset(mixed, 0, sizeof(mixed));
        storeWord(mixed, 0, packFloatWord(.999f, format), format);
        addWord(mixed, 0, packFloatWord(.999f, format), format);
        if (std::abs(readSampleFloat(mixed, 0, format) - 1.f) > .0001f) {
            return toJString(env, "FAIL positive saturation");
        }
        std::memset(mixed, 0, sizeof(mixed));
        storeWord(mixed, 0, packFloatWord(-.999f, format), format);
        addWord(mixed, 0, packFloatWord(-.999f, format), format);
        if (std::abs(readSampleFloat(mixed, 0, format) + 1.f) > .0001f) {
            return toJString(env, "FAIL negative saturation");
        }
    }
    // Block publication, channel extraction, additive mixing and counter wrap.
    Ring blocks;
    blocks.write.store(UINT32_MAX - 127);
    blocks.read.store(UINT32_MAX - 127);
    std::array<int16_t, 384 * 3> source;
    std::array<int16_t, 384 * 2> block{};
    for (int f = 0; f < 384; ++f) {
        source[f * 3] = -100;
        source[f * 3 + 1] = static_cast<int16_t>(f);
        source[f * 3 + 2] = 100;
    }
    for (int n = 0; n < 1000; ++n) {
        block.fill(0);
        if (blocks.pushChannel(source.data(), 384, oboe::AudioFormat::I16, 3, 1) != 384 ||
            blocks.mix(block.data(), 384, oboe::AudioFormat::I16, 2, 1) != 384) {
            return toJString(env, "FAIL block wrap/count");
        }
        for (int f = 0; f < 384; ++f) {
            if (block[f * 2] != 0 || block[f * 2 + 1] != static_cast<int16_t>(f)) {
                return toJString(env, "FAIL block channels/mix");
            }
        }
    }
    // Mixing sums into the destination domain with saturation.
    block.fill(0);
    std::array<int16_t, 4> loud{{30000, 30000, 30000, 30000}};
    blocks.pushChannel(loud.data(), 4, oboe::AudioFormat::I16, 1, 0);
    blocks.pushChannel(loud.data(), 4, oboe::AudioFormat::I16, 1, 0);
    blocks.mix(block.data(), 4, oboe::AudioFormat::I16, 1, 0);
    blocks.mix(block.data(), 4, oboe::AudioFormat::I16, 1, 0);
    for (int k = 0; k < 4; ++k) {
        if (block[k] != 32767) return toJString(env, "FAIL saturating block mix");
    }
    // Actual concurrent SPSC block traffic, without relying on scheduling order.
    Ring concurrent;
    std::atomic<bool> bad{false};
    std::thread producer([&] {
        std::array<int16_t, 64> samples;
        for (int offset = 0; offset < 65536;) {
            for (int i = 0; i < 64; ++i) samples[i] = static_cast<int16_t>((offset + i) & 0x7fff);
            offset += concurrent.pushChannel(samples.data(), 64, oboe::AudioFormat::I16, 1, 0);
        }
    });
    for (int offset = 0; offset < 65536;) {
        std::array<int16_t, 64> samples{};
        const int count = concurrent.mix(samples.data(), 64, oboe::AudioFormat::I16, 1, 0);
        for (int i = 0; i < count; ++i) {
            if (samples[i] != static_cast<int16_t>((offset + i) & 0x7fff)) bad.store(true);
        }
        offset += count;
    }
    producer.join();
    if (bad.load()) return toJString(env, "FAIL concurrent block order");
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
    // Multiple channels on one output bypass rings; cross-output fanout does not.
    Engine topology;
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
        !topology.routes[2]->ring || !topology.routes[3]->ring) {
        return toJString(env, "FAIL transfer topology");
    }
    // Zero-margin route: consume what is available, trim a stall backlog, and
    // leave a short block's tail silent without a re-accumulation threshold.
    auto &route = *topology.routes[2];
    std::array<int16_t, 16> mono;
    mono.fill(100);
    std::array<int16_t, 32> output{};
    route.ring->pushChannel(mono.data(), 16, oboe::AudioFormat::I16, 1, 0);
    if (route.mix(output.data(), 16, oboe::AudioFormat::I16) != 16) {
        return toJString(env, "FAIL route passthrough");
    }
    for (int f = 0; f < 16; ++f) {
        if (output[f * 2] != 100 || output[f * 2 + 1] != 0) {
            return toJString(env, "FAIL route channel mapping");
        }
    }
    output.fill(0);
    if (route.mix(output.data(), 16, oboe::AudioFormat::I16) != 0 ||
        route.underflows.load() != 1 || output[0] != 0) {
        return toJString(env, "FAIL silent tail without re-prime threshold");
    }
    // Short block: only valid frames mix; the rest stays silent immediately.
    route.ring->pushChannel(mono.data(), 8, oboe::AudioFormat::I16, 1, 0);
    output.fill(0);
    if (route.mix(output.data(), 16, oboe::AudioFormat::I16) != 8 ||
        output[7 * 2] != 100 || output[8 * 2] != 0) {
        return toJString(env, "FAIL short-read zero tail");
    }
    // A scheduling stall trims its backlog instead of playing it back late.
    while (route.ring->pushChannel(mono.data(), 16, oboe::AudioFormat::I16, 1, 0) > 0) {}
    const auto trimmedBefore = route.trimmed.load();
    output.fill(0);
    if (route.mix(output.data(), 16, oboe::AudioFormat::I16) != 16 ||
        route.ring->available() != 0 || route.trimmed.load() <= trimmedBefore) {
        return toJString(env, "FAIL stall backlog trim");
    }
    if (topology.routes[3]->ring->available() != 0) {
        return toJString(env, "FAIL fanout block isolation");
    }
    return toJString(env, "PASS raw I16/I24/I32/Float word pipeline, saturating mix, callback lifetime, "
        "block SPSC/wrap, direct/fanout topology, zero-tail short reads, backlog trim, "
        "probe rollback/cooldown");
}

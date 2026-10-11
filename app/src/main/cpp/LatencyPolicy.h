#pragma once

#include <algorithm>
#include <cstdint>

// Callback-owned state. Only the published snapshots in Endpoint are atomic.
// Capacity is a safety limit, not a target amount of queued audio.
struct InputGuard {
    int frames = 0;
    int step = 1;
    int limit = 0;
    int previous = 0;
    bool primed = false;
    bool received = false;
    bool probing = false;
    uint64_t stable = 0;
    uint64_t trial = 0;
    int cooldownSeconds = 10;
    uint32_t shortReads = 0;
    uint32_t reprimes = 0;
    uint32_t probes = 0;
    uint32_t failedProbes = 0;

    void prepare(int callbackFrames, int capacity, int inputBurst, int outputBurst, int rate) {
        const int burst = std::max({1, inputBurst, outputBurst});
        step = std::max(1, burst / 4);
        limit = std::max(0, std::min(burst * 4, capacity - callbackFrames));
        frames = std::min(frames, limit);
        previous = std::min(previous, limit);
        if (frames > 0 && !probing && stable >= static_cast<uint64_t>(rate) * cooldownSeconds) {
            previous = frames;
            frames = std::max(0, frames - step);
            probing = true;
            trial = stable = 0;
            ++probes;
        }
    }

    bool ready(int available, int requested) {
        // Zero guard forwards even a partial block, including at startup.
        if (!primed && frames > 0 && available < requested + frames) return false;
        primed = true;
        return true;
    }

    int discard(int available, int requested, int maximumRead) const {
        return std::min(maximumRead, std::max(0, available - requested - frames));
    }

    void completed(int requested, int count, int rate) {
        if (count < requested) {
            ++shortReads;
            // Do not inflate latency just because capture has not started yet.
            if (received || count > 0) {
                // A lone stall after successful playback should not permanently
                // add latency. Grow only during repeated shortages; a failed
                // reduction always restores its previously working value.
                const bool isolated = frames > 0 && stable >= static_cast<uint64_t>(rate) * 2;
                frames = std::min(limit, probing ? previous : isolated ? frames : frames + step);
                if (probing) {
                    ++failedProbes;
                    cooldownSeconds = std::min(120, std::max(30, cooldownSeconds * 2));
                }
                ++reprimes;
                primed = false;
            }
            stable = trial = 0;
            probing = false;
        } else {
            stable += count;
            if (probing && (trial += count) >= static_cast<uint64_t>(rate) * 10) probing = false;
        }
        received = received || count > 0;
    }
};

// LatencyTuner alone changes output size. This only chooses its next reset
// minimum and backs off reductions which caused an output underrun.
struct OutputProbe {
    int floor = 1;
    int rollback = 0;
    int cooldownSeconds = 15;
    uint64_t stable = 0;
    uint64_t trial = 0;
    uint32_t reductions = 0;
    uint32_t failedReductions = 0;

    // Positive: reduce via reset. Negative: restore the minimum only; the
    // tuner's xrun growth restores size. Resetting on xrun would let its stale
    // xrun baseline count the same underrun again and grow twice.
    int update(int size, int burst, int rate, int callbackFrames, bool xrun, bool inputGap) {
        if (xrun || inputGap) {
            stable = 0;
            if (rollback && xrun) {
                const int restore = std::max(rollback, size);
                rollback = 0;
                ++failedReductions;
                cooldownSeconds = std::min(120, std::max(30, cooldownSeconds * 2));
                return -restore;
            }
            trial = 0;
            return 0;
        }
        stable += callbackFrames;
        if (rollback && (trial += callbackFrames) >= static_cast<uint64_t>(rate) * 10) rollback = 0;
        if (!rollback && size > floor && stable >= static_cast<uint64_t>(rate) * cooldownSeconds) {
            rollback = size;
            stable = trial = 0;
            ++reductions;
            return std::max(floor, size - std::max(1, burst));
        }
        return 0;
    }
};

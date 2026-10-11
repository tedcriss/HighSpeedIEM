#pragma once

#include <algorithm>
#include <cstdint>

// Single minimum-latency policy (no selectable modes):
// - Inputs keep zero scheduling guard; a short read leaves its tail silent and
//   never triggers a re-accumulation threshold (doc sections 7/8).
// - Outputs rely on LatencyTuner alone for size changes. This probe only picks
//   the next reset minimum and backs off reductions which caused an underrun.
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
                floor = std::max(floor, restore); // retain the observed working lower bound
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

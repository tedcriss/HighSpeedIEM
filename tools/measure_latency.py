"""Measure input-to-output lag in a common-clock, stereo PCM WAV.

Channel 1 is the reference, channel 2 is the forwarded signal. Requires numpy.
Example: python tools/measure_latency.py capture.wav --events 2,4,6,8,10,12,14,16,18,20
Use isolated chirps/noise events; continuous music or separate recording clocks
cannot establish this app's latency. Event times select reference windows.
"""
import argparse
import json
from pathlib import Path
import wave

import numpy as np


def correlate(reference, output, max_lag, min_lag=0, rate=48000):
    x = np.asarray(reference, dtype=float)
    y = np.asarray(output, dtype=float)
    n = len(x)
    if n < 2 or len(y) < n + max_lag:
        raise ValueError("Recording too short for the selected event/search window")
    x = x - x.mean()
    energy = np.dot(x, x)
    if energy < 1e-12:
        return {"valid": False, "reason": "silent reference"}, np.zeros(max_lag + 1)
    fft_size = 1 << (len(y) + n - 2).bit_length()
    convolution = np.fft.irfft(np.fft.rfft(y, fft_size) * np.fft.rfft(x[::-1], fft_size), fft_size)
    numerator = convolution[n - 1:n + max_lag]
    sums = np.concatenate(([0.0], np.cumsum(y)))
    squares = np.concatenate(([0.0], np.cumsum(y * y)))
    local_sum = sums[n:n + max_lag + 1] - sums[:max_lag + 1]
    local_energy = squares[n:n + max_lag + 1] - squares[:max_lag + 1] - local_sum ** 2 / n
    score = np.clip(np.abs(numerator) / np.sqrt(energy * np.maximum(local_energy, 1e-30)), 0, 1)
    score[:min_lag] = 0
    lag = int(np.argmax(score))
    confidence = float(score[lag])
    alternatives = score.copy()
    separation = max(1, int(rate * .002))
    alternatives[max(min_lag, lag - separation):lag + separation + 1] = 0
    second = float(alternatives.max())
    ratio = confidence / max(second, 1e-12)
    valid = confidence >= .4 and ratio >= 1.15 and min_lag < lag < max_lag
    result = {"valid": valid, "lagFrames": lag, "lagMs": lag * 1000 / rate,
              "confidence": confidence, "secondPeak": second, "peakRatio": ratio,
              "reason": "ok" if valid else "weak/ambiguous/boundary peak"}
    return result, score


def read_pcm(path):
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 2 or wav.getcomptype() != "NONE":
            raise ValueError("Use uncompressed, common-clock stereo PCM WAV")
        rate, width = wav.getframerate(), wav.getsampwidth()
        raw = wav.readframes(wav.getnframes())
    if width == 1:
        samples = (np.frombuffer(raw, dtype=np.uint8).astype(float) - 128) / 128
    elif width in (2, 4):
        samples = np.frombuffer(raw, dtype="<i" + str(width)).astype(float) / (2 ** (width * 8 - 1))
    elif width == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        value = b[:, 0] | b[:, 1] << 8 | b[:, 2] << 16
        samples = ((value ^ 0x800000) - 0x800000).astype(float) / 8388608
    else:
        raise ValueError("Unsupported PCM sample width: " + str(width))
    return rate, samples.reshape(-1, 2)


def self_test():
    rng = np.random.default_rng(29)
    x = rng.normal(size=2048)
    for lag in (1, 600, 2000):
        y = rng.normal(scale=.03, size=len(x) + 2400)
        y[lag:lag + len(x)] += -.3 * x  # gain, phase inversion and noise
        result, _ = correlate(x, y, 2400)
        assert result["valid"] and result["lagFrames"] == lag, result
    y = np.zeros(len(x) + 6000)
    y[600:600 + len(x)] += x
    y[3600:3600 + len(x)] += x
    result, _ = correlate(x, y, 6000)
    assert not result["valid"] and result["peakRatio"] < 1.15, result
    result, _ = correlate(np.zeros_like(x), y, 6000)
    assert not result["valid"], result
    print("PASS known lags, noise, gain/inversion, ambiguous peaks, silence")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", nargs="?", type=Path)
    parser.add_argument("--events", help="Comma-separated reference-event start times in seconds")
    parser.add_argument("--window-ms", type=float, default=100)
    parser.add_argument("--max-ms", type=float, default=250)
    parser.add_argument("--min-ms", type=float, default=0)
    parser.add_argument("--output", type=Path, default=Path("latency-result.json"))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.wav or not args.events:
        parser.error("Provide a stereo WAV and --events")
    if not 0 <= args.min_ms < args.max_ms or args.window_ms <= 0:
        parser.error("Require 0 <= min-ms < max-ms and a positive window-ms")
    rate, samples = read_pcm(args.wav)
    n, maximum, minimum = (round(value * rate / 1000) for value in
                            (args.window_ms, args.max_ms, args.min_ms))
    events = []
    for value in args.events.split(","):
        when = float(value)
        if when < 0:
            raise ValueError("Event time must be nonnegative")
        start = round(when * rate)
        result, _ = correlate(samples[start:start + n, 0], samples[start:start + n + maximum, 1],
                              maximum, minimum, rate)
        events.append({"eventSeconds": when, **result})
    valid = np.array([event["lagMs"] for event in events if event["valid"]])
    report = {"wav": str(args.wav.resolve()), "sampleRate": rate,
              "referenceChannel": 1, "outputChannel": 2, "events": events,
              "validCount": len(valid), "invalidCount": len(events) - len(valid),
              "sufficientEvents": len(valid) >= 10,
              "summaryMs": ({"median": float(np.median(valid)), "p95": float(np.percentile(valid, 95)),
                             "maximum": float(valid.max()), "minimum": float(valid.min())} if len(valid) else None)}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

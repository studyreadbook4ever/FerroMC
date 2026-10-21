"""Shared synthetic acoustic proxy; intentionally contains no tonal rules."""

import numpy as np

PITCH_MIN, PITCHES, VOICES, EXPORT_BATCH = 36, 64, 4, 16
PARTIAL_AMPLITUDES = np.array([1.0, 0.25, 0.10], dtype=np.float64)


def roughness_matrix():
    midi = np.arange(PITCH_MIN, PITCH_MIN + PITCHES)
    fundamentals = 440.0 * 2.0 ** ((midi - 69) / 12.0)
    partials = fundamentals[:, None] * np.arange(1, 4)[None, :]
    left = partials[:, None, :, None]
    right = partials[None, :, None, :]
    distance = np.abs(left - right) * 0.24 / (0.021 * np.minimum(left, right) + 19.0)
    kernel = np.exp(-3.5 * distance) - np.exp(-5.75 * distance)
    amplitude = PARTIAL_AMPLITUDES[:, None] * PARTIAL_AMPLITUDES[None, :]
    roughness = (kernel * amplitude[None, None, :, :]).sum(axis=(2, 3))
    roughness = (roughness + roughness.T) / 2
    scale = roughness[~np.eye(PITCHES, dtype=bool)].mean()
    return (roughness / scale).astype(np.float32), float(scale)

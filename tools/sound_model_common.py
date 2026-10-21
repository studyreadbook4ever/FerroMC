"""Synthetic sound-model targets; no musical corpus, tonal labels, or pretrained data.

This is a low-cost spectral roughness proxy for Ferro Saw, NOT its exact DSP.
It approximates the documented saw/square mix, pre-filter tanh drive, moving
low-pass cutoff, decay envelope, and linear velocity gain using eight harmonics
and seven time samples. Bin targets marginalize the full 4**4 candidate grid.
"""
from functools import lru_cache
import itertools
import numpy as np

BIN_LOW = np.array([[0, 25, 50, 75]] * 3 + [[16, 44, 72, 100]])
BIN_HIGH = np.array([[24, 49, 74, 100]] * 3 + [[43, 71, 99, 127]])
CENTERS = (BIN_LOW + BIN_HIGH) / 2
TIMES = np.array([.004, .012, .035, .08, .16, .30, .48])
TIME_WEIGHTS = np.diff([0, .008, .0235, .0575, .12, .23, .39, .54])
HARMONICS = np.arange(1, 9)
MIX, STRENGTH = .35, 2.0


@lru_cache(maxsize=1)
def drive_spectra():
    phase = np.arange(2048) / 2048
    raw = .79 * (2 * phase - 1) + .21 * np.where(phase < .5, 1, -1)
    driven = np.tanh(raw[None, :] * (1 + 2 * np.arange(101)[:, None] / 100))
    return np.abs(np.fft.rfft(driven, axis=-1)[:, 1:9]) * 2 / 2048


def features(pitches, parameters):
    """Return [..., time, harmonic] amplitude features at exact integer settings."""
    tone, decay, drive, velocity = np.moveaxis(np.asarray(parameters, dtype=np.float64), -1, 0)
    tone, decay = tone / 100, decay / 100
    table = drive_spectra()
    lower = np.floor(drive).astype(int).clip(0, 100)
    upper = np.minimum(lower + 1, 100)
    fraction = (drive - lower)[..., None]
    spectrum = table[lower] * (1 - fraction) + table[upper] * fraction
    frequency = (440 * 2 ** ((np.asarray(pitches) - 69) / 12))[..., None] * HARMONICS
    cutoff = 110 + 3200 * tone[..., None] ** 2 + (700 + 6500 * tone[..., None]) * np.exp(-TIMES / (.022 + .09 * decay[..., None]))
    ratio = frequency[..., None, :] / cutoff[..., :, None]
    # Stable second-order magnitude approximation; exact Ferro TPT state is not modeled.
    q = .59 + .23 * tone
    filtering = 1 / np.sqrt((1 - ratio ** 2) ** 2 + (ratio / q[..., None, None]) ** 2)
    duration = .10 + .44 * decay
    envelope = (1 - np.exp(-TIMES / .0015)) * np.exp(-TIMES / (.035 + .16 * decay[..., None]))
    envelope *= np.clip((duration[..., None] - TIMES) / .02, 0, 1)
    antialias = 1 / (1 + (frequency / 14500) ** 4)
    return (spectrum[..., None, :] * filtering * antialias[..., None, :]
            * envelope[..., :, None] * (velocity / 127)[..., None, None]
            * np.sqrt(TIME_WEIGHTS)[..., None]).astype(np.float32)


@lru_cache(maxsize=1)
def acoustic_tables():
    frequencies = (440 * 2 ** ((np.arange(36, 100) - 69) / 12))[:, None] * HARMONICS
    left, right = frequencies[:, None, :, None], frequencies[None, :, None, :]
    distance = np.abs(left - right) * .24 / (.021 * np.minimum(left, right) + 19)
    kernel = (np.exp(-3.5 * distance) - np.exp(-5.75 * distance)).astype(np.float32)
    triples = np.array(list(itertools.product(range(4), repeat=3)))
    parameters = np.column_stack([CENTERS[i, triples[:, i]] for i in range(3)] + [np.full(64, 127.)])
    candidate_features = features(np.arange(36, 100)[:, None], np.broadcast_to(parameters, (64, 64, 4)))
    # Fixed, separate random reference calibrates the strength, without a musical target.
    rng = np.random.default_rng(19019)
    pitches = np.argsort(rng.random((4096, 64)), axis=1)[:, :2] + 36
    params = random_parameters(rng, (4096, 2))
    partials = features(pitches, params)
    values = np.einsum('nth,nhk,ntk->n', partials[:, 0], kernel[pitches[:, 0]-36, pitches[:, 1]-36], partials[:, 1], optimize=True)
    return kernel, candidate_features, float(values.mean())


def random_parameters(rng, shape):
    result = rng.integers(0, 101, (*shape, 4))
    result[..., 3] = rng.integers(16, 128, shape)
    return result


def context(pitches, counts, parameters, missing):
    active = np.arange(4)[None, :] < counts[:, None]
    result = np.zeros((len(pitches), 4, 8), dtype=np.float32)
    result[:, :, 0] = (pitches - 36) / 63
    result[:, :, 1] = 1
    result[:, :, 2:6] = parameters / np.array([100, 100, 100, 127])
    result[~active] = 0
    result[np.arange(len(pitches)), missing, 2:6] = 0
    result[np.arange(len(pitches)), missing, 6] = 1
    return result.reshape(-1, 32)


def targets(pitches, counts, parameters, missing):
    kernel, candidates, scale = acoustic_tables()
    output = []
    for start in range(0, len(pitches), 512):
        p, n, values, mask = pitches[start:start+512], counts[start:start+512], parameters[start:start+512], missing[start:start+512]
        features_other = features(p, values)
        active = (np.arange(4)[None, :] < n[:, None]) & (np.arange(4)[None, :] != mask[:, None])
        features_other *= active[:, :, None, None]
        chosen_pitch = p[np.arange(len(p)), mask] - 36
        coupling = np.einsum('nvhk,nvtk->nth', kernel[chosen_pitch[:, None], p-36], features_other, optimize=True)
        # Every tone/decay/drive triple, then every velocity: 256 candidates.
        energies = np.einsum('ncth,nth->nc', candidates[chosen_pitch], coupling, optimize=True)
        energies = energies[:, :, None] * (CENTERS[3] / 127)[None, None, :] / (n[:, None, None]-1) / scale
        logits = -STRENGTH * energies.reshape(-1, 256)
        preferred = np.exp(logits - logits.max(axis=1, keepdims=True))
        preferred /= preferred.sum(axis=1, keepdims=True)
        joint = ((1-MIX)/256 + MIX*preferred).reshape(-1, 4, 4, 4, 4)
        output.append(np.stack([joint.sum(axis=tuple(a for a in range(1, 5) if a != parameter+1)) for parameter in range(4)], axis=1))
    return np.concatenate(output).astype(np.float32)


def make_dataset(seed, count, split):
    rng = np.random.default_rng(seed)
    collected, pitch_batches, count_batches = 0, [], []
    while collected < count:
        proposal_count = max(4096, (count-collected)*(2 if split == 'train' else 12))
        n = rng.integers(2, 5, proposal_count)
        active = np.arange(4)[None, :] < n[:, None]
        raw = np.argsort(rng.random((proposal_count, 64)), axis=1)[:, :4] + 36
        p = np.sort(np.where(active, raw, 100), axis=1)
        p[np.arange(4)[None, :] >= n[:, None]] = 36
        bucket = (p[:, 0]*73856093 ^ p[:, 1]*19349663 ^ p[:, 2]*83492791 ^ p[:, 3]*2654435761 ^ n*97531) % 10
        keep = bucket < 8 if split == 'train' else bucket == (8 if split == 'validation' else 9)
        selected = p[keep][:count-collected]
        pitch_batches.append(selected); count_batches.append(n[keep][:len(selected)])
        collected += len(selected)
    pitches, counts = np.concatenate(pitch_batches), np.concatenate(count_batches)
    parameters = random_parameters(rng, (count, 4))
    missing = (rng.random(count)*counts).astype(np.int64)
    return {'input': context(pitches, counts, parameters, missing), 'missing': missing,
            'targets': targets(pitches, counts, parameters, missing),
            'pitches': pitches, 'counts': counts, 'parameters': parameters}


def chord_scores(pitches, parameters):
    kernel, _, scale = acoustic_tables()
    partials = features(pitches, parameters)
    result = np.zeros(len(pitches))
    voices = pitches.shape[1]
    for a in range(voices):
        for b in range(a+1, voices):
            result += np.einsum('nth,nhk,ntk->n', partials[:, a], kernel[pitches[:, a]-36, pitches[:, b]-36], partials[:, b], optimize=True)
    return result / (voices*(voices-1)/2) / scale

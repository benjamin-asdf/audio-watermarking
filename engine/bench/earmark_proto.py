"""Earmark engine prototype v2 (see engine/DESIGN.md). Clean-room, literature-based.

Mark = +-delta dB gain on small time-frequency cells (23 ms x 86 Hz) between 750 Hz and 4.8 kHz.
Blocks of 256 x 47 cells (5.94 s) repeat through the track. A quarter of the cells carry a known
pilot for synchronisation; the rest carry a K=7 rate-1/3 tail-biting convolutional code of the
128-bit payload, ~23 chips per coded bit, with ISS host-interference cancellation at embed time.

Many small independent chips beat few large ones: music moves coherently across a large tile
(4-10 dB host noise per tile, measured), but much less coherently across small cells after local
2-D whitening.

Detector: per ~1-block segment, band log-energies at a fine hop -> local 2-D whitening -> fold
modulo the block period -> pilot circular cross-correlation over a grid of frequency scales
(pitch) and time scales (tempo), coarse then fine -> soft bits weighted by pilot strength, summed
across segments -> exact tail-biting Viterbi from every start state = ranked candidate list.
"""
import numpy as np
from scipy.ndimage import uniform_filter
from scipy.signal import stft, istft

import os

SR = 44100
## Cell geometry is tunable via env for experiments (EM_NFFT, EM_TU); see proto_grid.py.
N_FFT = int(os.environ.get("EM_NFFT", 1024))
HOP = N_FFT // 2          ## synthesis hop
FINE_HOP = 128            ## detector analysis hop
TU = int(os.environ.get("EM_TU", 1024))      ## samples per cell (time unit)
BINS_PER_CELL = max(1, N_FFT // 512)         ## ~86 Hz per frequency cell
F_LO = float(os.environ.get("EM_FLO", 775))   ## lowest marked frequency (Hz)
BIN_LO = round(F_LO / (SR / N_FFT))
N_F = round((4800 - F_LO) / (BINS_PER_CELL * SR / N_FFT))   ## cells up to ~4.8 kHz
BIN_HI = BIN_LO + N_F * BINS_PER_CELL
BLOCK = 262144            ## samples per block = 5.94 s
N_T = BLOCK // TU         ## time cells per block
N_CELLS = N_T * N_F
N_PILOT = N_CELLS // 4
PAYLOAD_BITS = int(os.environ.get("EM_BITS", 96))   ## 96: ver 4 + work-id 44 + HMAC tag 48
CODED_BITS = 3 * PAYLOAD_BITS
CHIPS = (N_CELLS - N_PILOT) // CODED_BITS
KEY = 0x5EED_EA2_3A4C     ## public: detection is public by design
GENS = (0o133, 0o171, 0o165)

ALPHA = 1.0               ## dB per cell
ISS_LAMBDA = 0.8
ALPHA_MAX = 2.5           ## dB clamp per cell
SILENCE_REL_DB = 90.0     ## cells this far below the track's loudest cell are left alone


def layout():
    rng = np.random.default_rng(KEY)
    perm = rng.permutation(N_CELLS)
    pilot = perm[:N_PILOT]
    pilot_s = rng.choice([-1.0, 1.0], N_PILOT)
    data = perm[N_PILOT:N_PILOT + CODED_BITS * CHIPS].reshape(CODED_BITS, CHIPS)
    data_s = rng.choice([-1.0, 1.0], (CODED_BITS, CHIPS))
    return pilot, pilot_s, data, data_s


PILOT, PILOT_S, DATA, DATA_S = layout()


def cell_tf(cells):
    return cells // N_F, cells % N_F     ## time cell, freq cell


## ---------------------------------------------------------------------------- coding

def _parity(x):
    x = np.asarray(x)
    p = np.zeros_like(x)
    while np.any(x):
        p ^= x & 1
        x = x >> 1
    return p


def conv_encode(bits):
    u = np.asarray(bits, dtype=np.int64)
    state = int(sum(int(u[-1 - j]) << (5 - j) for j in range(6)))   ## tail-biting start
    out = []
    for b in u:
        reg = (int(b) << 6) | state
        out += [int(_parity(reg & g)) for g in GENS]
        state = reg >> 1
    return np.array(out)


_NS = np.arange(64)
_U = _NS >> 5
_PS = np.stack([((_NS & 31) << 1) | 0, ((_NS & 31) << 1) | 1])        ## predecessors (2, 64)
_REG = (_U[None, :] << 6) | _PS
_SYM = np.stack([2 * _parity(_REG & g) - 1 for g in GENS], -1)        ## (2, 64, 3) in +-1


def list_viterbi(soft):
    """soft: (CODED_BITS,), positive = 1. Exact tail-biting ML per start state.
    Returns [(metric, bits)] for all 64 start states, best first."""
    y = soft.reshape(PAYLOAD_BITS, 3)
    m = np.full((64, 64), -np.inf)
    m[_NS, _NS] = 0.0
    choices = np.zeros((PAYLOAD_BITS, 64, 64), dtype=np.int8)
    for i in range(PAYLOAD_BITS):
        bm = (_SYM * y[i]).sum(-1)
        cand = m[:, _PS] + bm[None]
        c = np.argmax(cand, axis=1)
        choices[i] = c
        m = np.take_along_axis(cand, c[:, None, :], 1)[:, 0]
    out = []
    for s in range(64):
        state, bits = s, np.zeros(PAYLOAD_BITS, dtype=np.int64)
        for i in range(PAYLOAD_BITS - 1, -1, -1):
            bits[i] = state >> 5
            state = _PS[choices[i, s, state], state]
        out.append((m[s, s], bits))
    out.sort(key=lambda t: -t[0])
    return out


## ---------------------------------------------------------------------------- features

def stft_power(mono, hop):
    _, _, z = stft(mono, SR, window="hann", nperseg=N_FFT, noverlap=N_FFT - hop,
                   boundary="zeros", padded=True)
    return (np.abs(z) ** 2).T          ## (frames, bins); frame t centred at t*hop


def cell_freq_matrix(freq_scale=1.0):
    """(n_bins, N_F) pooling: bin power -> frequency cells, cell edges scaled by freq_scale."""
    nb = N_FFT // 2 + 1
    edges = (BIN_LO + BINS_PER_CELL * np.arange(N_F + 1)) * freq_scale
    m = np.zeros((nb, N_F))
    for j in range(N_F):
        lo, hi = edges[j], edges[j + 1]
        for k in range(int(np.floor(lo)), int(np.ceil(hi)) + 1):
            if 0 <= k < nb:
                ## overlap of bin k, taken as [k, k+1) like the embedder, with [lo, hi)
                m[k, j] = max(0.0, min(hi, k + 1.0) - max(lo, float(k)))
    return m


LOUD_WEIGHT = os.environ.get("EM_LOUDW") == "1"


def log_cells(power, freq_scale=1.0):
    return 10 * np.log10(power @ cell_freq_matrix(freq_scale) + 1e-12)


def loudness_weight(e_db):
    """Cells far below the loudest cell of their frame are what codecs discard: weight them down
    (1 within 30 dB of the frame peak, fading to 0 at 50 dB below)."""
    rel = e_db - e_db.max(1, keepdims=True)
    return np.clip((rel + 50) / 20, 0, 1)


def whiten(e_db, t_cells_span, normalise=True):
    """Local 2-D whitening on (time frames, freq cells): subtract the mean of a neighbourhood of
    +-2 cells in time and frequency, clip outliers (transients)."""
    w = e_db - uniform_filter(e_db, size=(5 * t_cells_span, 5), mode="nearest")
    if not normalise:
        return w
    ## Matched-detector weighting: divide by the local noise level (mean |w| over ~9 time x 5
    ## frequency cells), so noisy regions (sparse harmonics, transients) count less.
    local = uniform_filter(np.abs(w), size=(9 * t_cells_span, 5), mode="nearest") * 1.25
    w = w / (local + 1e-3)
    return np.clip(w, -3, 3)


## ---------------------------------------------------------------------------- embed

def embed(x, sr, payload_bits):
    assert sr == SR, "prototype: 44.1 kHz only"
    n = len(x)
    mono = x.mean(1)
    target = 2.0 * conv_encode(payload_bits) - 1.0

    ## Host cell values in dB (synthesis-hop frames, 2 per time cell), same whitening shape as
    ## the detector, unnormalised so ISS works in dB.
    e = log_cells(stft_power(mono, HOP))
    fpt = TU // HOP
    n_tc = int(np.ceil(n / TU))
    e = np.pad(e, ((0, max(0, n_tc * fpt - len(e))), (0, 0)), mode="edge")[: n_tc * fpt]
    e_cell = e.reshape(n_tc, fpt, N_F).mean(1)
    host = whiten(e_cell, 1, normalise=False)

    delta = np.zeros((n_tc, N_F))
    pt, pf = cell_tf(PILOT)
    dt, df = cell_tf(DATA)
    for k in range(int(np.ceil(n_tc / N_T))):
        base = k * N_T
        okp = base + pt < n_tc
        delta[base + pt[okp], pf[okp]] = ALPHA * PILOT_S[okp]
        valid = base + dt < n_tc
        h = np.where(valid, host[np.minimum(base + dt, n_tc - 1), df] * DATA_S, 0.0)
        proj = h.sum(1) / np.maximum(valid.sum(1), 1)                       ## dB
        amp = np.clip(ALPHA * target - ISS_LAMBDA * proj, -ALPHA_MAX, ALPHA_MAX)
        d = DATA_S * amp[:, None]
        delta[base + dt[valid], df[valid]] = d[valid]
    delta[e_cell < e_cell.max() - SILENCE_REL_DB] = 0.0

    ## Cells -> per-bin gains; apply to every channel at the synthesis hop.
    spread = np.zeros((N_F, N_FFT // 2 + 1))
    for j in range(N_F):
        spread[j, BIN_LO + BINS_PER_CELL * j: BIN_LO + BINS_PER_CELL * (j + 1)] = 1.0
    out = np.empty_like(x)
    for ch in range(x.shape[1]):
        _, _, z = stft(x[:, ch], SR, window="hann", nperseg=N_FFT, noverlap=N_FFT - HOP,
                       boundary="zeros", padded=True)
        tc = np.minimum((np.arange(z.shape[1]) * HOP) // TU, n_tc - 1)
        gain_db = delta[tc] @ spread
        z = z * (10 ** (gain_db.T / 20))
        _, y = istft(z, SR, window="hann", nperseg=N_FFT, noverlap=N_FFT - HOP, boundary=True)
        out[:, ch] = y[:n]
    return out.astype(np.float32)


## ---------------------------------------------------------------------------- detect

FREQ_SCALES = np.round(np.arange(0.97, 1.0301, 0.005), 4)
COARSE_TIME = np.round(np.arange(0.97, 1.0301, 0.005), 4)
FINE_STEP = 0.0005
P = BLOCK // FINE_HOP     ## fold bins per block (2048)
FPT = TU // FINE_HOP      ## fold bins per time cell (8)
MIN_SEGMENT = 0.6         ## of a block


def fold(w, start_frame, time_scale):
    """Fold whitened fine frames (absolute frame index start_frame + i) into P block-phase bins,
    then average over each time cell (circular box of FPT bins)."""
    t = (start_frame + np.arange(len(w))) * FINE_HOP * time_scale
    bins = ((t % BLOCK) // FINE_HOP).astype(np.int64) % P
    s = np.zeros((P, N_F))
    c = np.zeros(P)
    np.add.at(s, bins, w)
    np.add.at(c, bins, 1)
    f = np.where(c[:, None] > 0, s / np.maximum(c, 1)[:, None], 0.0)
    cs = np.concatenate([np.zeros((1, N_F)), np.cumsum(np.concatenate([f, f[:FPT]]), 0)])
    return (cs[FPT:FPT + P] - cs[:P]) / FPT


_PT, _PF = cell_tf(PILOT)
_H = np.zeros((P, N_F))
np.add.at(_H, (_PT * FPT, _PF), PILOT_S)
_H_FFT = np.conj(np.fft.rfft(_H, axis=0))


def pilot_z(g):
    sc = np.fft.irfft((np.fft.rfft(g, axis=0) * _H_FFT).sum(1), n=P)
    z = (sc - sc.mean()) / (sc.std() + 1e-9)
    o = int(np.argmax(z))
    return z[o], o


def soft_bits(g, o):
    dt, df = cell_tf(DATA)
    v = g[(o + dt * FPT) % P, df] * DATA_S
    y = v.sum(1)
    return y / (y.std() + 1e-9)


CHUNK_BLOCKS = 4          ## clips are processed in chunks of up to 4 blocks (~24 s)
COARSE_KEEP = 3


def sync_chunk(w_by_fs, start, stop):
    """Coarse search (all freq scales x coarse time scales) on the first block of the chunk,
    where a 0.5% tempo step drifts < 1 cell; then refine the time scale in 0.05% steps over the
    whole chunk for the best few, so every block of the chunk adds to the pilot.
    Returns up to COARSE_KEEP hypotheses (z, fs, ts, o, g), best first."""
    first = slice(start, min(stop, start + P))
    coarse = []
    for fs, w in w_by_fs.items():
        for ts in COARSE_TIME:
            z, _ = pilot_z(fold(w[first], first.start, ts))
            coarse.append((z, fs, ts))
    coarse.sort(key=lambda t: -t[0])
    hyps = []
    for _, fs, ts0 in coarse[:COARSE_KEEP]:
        best = (-np.inf,)
        for ts in np.arange(ts0 - 0.003, ts0 + 0.00301, FINE_STEP):
            g = fold(w_by_fs[fs][start:stop], start, ts)
            z, o = pilot_z(g)
            if z > best[0]:
                best = (z, fs, ts, o, g)
        hyps.append(best)
    hyps.sort(key=lambda h: -h[0])
    return hyps


## Sync hypotheses below this pilot z are ignored. Kept low on purpose: wrong hypotheses only add
## candidates, and the server's 61-bit HMAC tag rejects those (false accept ~ L * 2^-61).
Z_MIN = 4.0
PER_DECODE = 8            ## Viterbi list entries kept per decoded hypothesis


def _decode(soft):
    return list_viterbi(soft / (soft.std() + 1e-9))[:PER_DECODE]


def detect_list(x, sr, list_size=64):
    """Returns (candidate bit arrays best first, info). Candidates come from the z-weighted
    combination of each chunk's best hypothesis, plus every individual hypothesis."""
    assert sr == SR, "prototype: 44.1 kHz only"
    power = stft_power(x.mean(1), FINE_HOP)
    w_by_fs = {}
    for fs in FREQ_SCALES:
        e = log_cells(power, fs)
        w_by_fs[fs] = whiten(e, FPT) * (loudness_weight(e) if LOUD_WEIGHT else 1.0)
    n = len(power)
    span = CHUNK_BLOCKS * P
    starts = [s for s in range(0, n, span) if (min(n, s + span) - s) >= MIN_SEGMENT * P] or [0]
    total = np.zeros(CODED_BITS)
    decoded = []
    info = []
    for s in starts:
        hyps = [h for h in sync_chunk(w_by_fs, s, min(n, s + span)) if h[0] >= Z_MIN]
        info.append([(round(float(h[0]), 1), float(h[1]), round(float(h[2]), 4)) for h in hyps])
        for i, (z, fs, ts, o, g) in enumerate(hyps):
            sb = soft_bits(g, o)
            if i == 0:
                total += z * sb
            decoded += [(m, b, z) for m, b in _decode(sb)]
    if total.any():
        decoded += [(m, b, np.inf) for m, b in _decode(total)]   ## combined first on ties
    decoded.sort(key=lambda t: (-t[0], -t[2]))
    out, seen = [], set()
    for _, b, _ in decoded:
        k = b.tobytes()
        if k not in seen:
            seen.add(k)
            out.append(b)
    return out[:list_size], {"segments": info}

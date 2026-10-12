"""Original RetroTV menu music + UI sounds, synthesized from scratch (no samples)."""
import numpy as np, subprocess, os, wave
from scipy.signal import fftconvolve, butter, sosfilt

SR = 44100
OUT = os.path.dirname(os.path.abspath(__file__))
rng = np.random.default_rng(7)

def note(n):  # MIDI -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def lowpass(x, hz, order=2):
    return sosfilt(butter(order, hz, btype='low', fs=SR, output='sos'), x)

def highpass(x, hz, order=2):
    return sosfilt(butter(order, hz, btype='high', fs=SR, output='sos'), x)

def env_adsr(n, a, d, s, r, total):
    t = np.arange(total) / SR
    e = np.zeros(total)
    a_n, d_n, r_n = int(a*SR), int(d*SR), int(r*SR)
    hold = max(n - a_n - d_n, 0)
    seg = [np.linspace(0, 1, a_n, endpoint=False), np.linspace(1, s, d_n, endpoint=False), np.full(hold, s),
           np.linspace(s, 0, r_n)]
    e_full = np.concatenate(seg)[:total]
    e[:len(e_full)] = e_full
    return e

def reverb_ir(seconds=3.2, damp=6000):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = rng.standard_normal((2, n)) * np.exp(-t * 2.1)
    ir = np.stack([lowpass(ch, damp) for ch in ir])
    ir[:, :int(0.012*SR)] *= 0.0  # pre-delay
    # normalise by energy, so the reverb is about as loud as the dry sound (not 30x louder)
    return ir / np.sqrt((ir ** 2).sum(axis=1, keepdims=True))

# ------------------------------------------------------------------ music
BPM = 78
BEAT = 60 / BPM
BAR = BEAT * 4
# Dmaj9 | Bm9 | Gmaj7(#11) | A6sus  (x2, second time ending on A then back to D)
CHORDS = [
    [50, 57, 61, 64, 66],   # D A C# E F#   (Dmaj9)
    [47, 54, 57, 61, 62],   # B F# A C# D   (Bm9)
    [43, 50, 54, 57, 61],   # G D F# A C#   (Gmaj9)
    [45, 52, 54, 59, 64],   # A E F# B E    (A6sus)
]
BARS = 16
LEN = int(BARS * BAR * SR)
music = np.zeros((2, LEN + int(4 * SR)))

def add(sig, start, pan=0.0, gain=1.0):
    i = int(start * SR)
    l = np.cos((pan + 1) * np.pi / 4)
    r = np.sin((pan + 1) * np.pi / 4)
    end = min(i + len(sig), music.shape[1])
    music[0, i:end] += sig[:end - i] * l * gain
    music[1, i:end] += sig[:end - i] * r * gain

def pad_voice(freq, dur):
    n = int((dur + 1.5) * SR)
    t = np.arange(n) / SR
    s = np.zeros(n)
    for det in (-0.07, 0.0, 0.06):  # gentle chorus
        f = freq * 2 ** (det / 12)
        for h, a in ((1, 1.0), (2, 0.35), (3, 0.15), (4, 0.06)):
            s += a * np.sin(2 * np.pi * f * h * t + rng.uniform(0, 6.28))
    s = lowpass(s, 1800)
    e = env_adsr(int(dur * SR), 1.2, 0.8, 0.75, 1.5, n)
    lfo = 1 + 0.08 * np.sin(2 * np.pi * 0.21 * t)
    return s * e * lfo

def keys_note(freq, dur=1.6):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = (np.sin(2*np.pi*freq*t) + 0.45*np.sin(2*np.pi*2*freq*t) * np.exp(-t*6)
         + 0.2*np.sin(2*np.pi*3.01*freq*t) * np.exp(-t*9))
    e = np.exp(-t * 2.6) * np.minimum(1, t / 0.004)
    return s * e

def bass_note(freq, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = np.sin(2*np.pi*freq*t) + 0.25*np.sin(2*np.pi*2*freq*t)
    e = np.minimum(1, t / 0.03) * np.exp(-t * 0.9)
    e[-int(0.08*SR):] *= np.linspace(1, 0, int(0.08*SR))
    return lowpass(s * e, 400)

def shaker(dur=0.09):
    n = int(dur * SR)
    t = np.arange(n) / SR
    s = highpass(rng.standard_normal(n), 6000) * np.exp(-t * 55)
    return s

# arpeggio pattern (indices into chord tones, 8th notes), varies per bar pair
ARPS = [[0, 2, 3, 4, 3, 2, 4, 3], [1, 3, 4, 2, 4, 3, 1, 2]]

for bar in range(BARS):
    chord = CHORDS[bar % 4]
    t0 = bar * BAR
    # pad (whole bar, upper 4 tones)
    for n_ in chord[1:]:
        add(pad_voice(note(n_), BAR), t0, pan=rng.uniform(-0.4, 0.4), gain=0.05)
    # bass on beats 1 and 3 (2nd half of the loop gets a walking pickup)
    root = chord[0] - 12
    add(bass_note(note(root), BEAT * 2 - 0.05), t0, gain=0.22)
    add(bass_note(note(root + (7 if bar % 2 else 0)), BEAT * 2 - 0.05), t0 + 2 * BEAT, gain=0.18)
    # keys arpeggio, enters after 4 bars, sparser in last 2 bars for a soft loop point
    if bar >= 4:
        pat = ARPS[(bar // 2) % 2]
        for i, idx in enumerate(pat):
            if bar >= 14 and i % 2: continue
            tone = chord[idx] + 12
            add(keys_note(note(tone)), t0 + i * BEAT / 2, pan=(-0.5 if i % 2 else 0.5), gain=0.085 * (0.8 if i % 2 else 1))
    # soft shaker on off-beats from bar 8
    if 8 <= bar < 15:
        for b in range(4):
            add(shaker(), t0 + b * BEAT + BEAT / 2, pan=0.3, gain=0.025)
    # occasional high sparkle
    if bar % 4 == 3:
        add(keys_note(note(chord[-1] + 24), 2.5), t0 + 3.5 * BEAT, pan=-0.2, gain=0.03)

# reverb (wet) and fold the tail back to the start so the loop is seamless
ir = reverb_ir()
wet = np.stack([fftconvolve(music[c], ir[c])[:music.shape[1]] for c in range(2)])
mix = music + wet * 0.45
loop = mix[:, :LEN].copy()
tail = mix[:, LEN:]
loop[:, :tail.shape[1]] += tail
# master: set a calm background level (about -17 dBFS RMS), then a gentle limiter for stray peaks
rms = np.sqrt((loop ** 2).mean())
loop = loop * (10 ** (-17 / 20) / rms)
loop = np.where(np.abs(loop) > 0.6, np.sign(loop) * (0.6 + 0.3 * np.tanh((np.abs(loop) - 0.6) / 0.3)), loop)

def write_wav(path, data):
    data = np.atleast_2d(data)
    pcm = (np.clip(data, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, 'wb') as w:
        w.setnchannels(pcm.shape[0]); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(pcm.T.tobytes())

def encode(wav, ogg, q):
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', str(q), ogg], check=True)

write_wav(f'{OUT}/menu_music.wav', loop)
encode(f'{OUT}/menu_music.wav', f'{OUT}/menu_music.ogg', 4)

# ------------------------------------------------------------------ UI sounds
def tone(freq, dur, decay, gain=1.0, harm=0.25):
    n = int(dur * SR); t = np.arange(n) / SR
    s = np.sin(2*np.pi*freq*t) + harm*np.sin(2*np.pi*2*freq*t)
    return s * np.exp(-t * decay) * np.minimum(1, t / 0.002) * gain

def place(total, parts):
    out = np.zeros(int(total * SR))
    for start, sig in parts:
        i = int(start * SR); out[i:i+len(sig)] += sig[:len(out)-i]
    return out

def finish(x, peak=0.7):
    x = x.copy()
    fade = int(0.01 * SR); x[-fade:] *= np.linspace(1, 0, fade)
    return x / np.abs(x).max() * peak

sfx = {
    # soft "tick" for moving between items
    'move': finish(place(0.09, [(0, tone(note(93), 0.09, 70, harm=0.1)), (0, tone(note(105), 0.05, 140, 0.25))]), 0.42),
    # two-note rising chime for select
    'select': finish(place(0.32, [(0, tone(note(88), 0.3, 18)), (0.06, tone(note(95), 0.26, 16))]), 0.6),
    # gentle falling blip for back
    'back': finish(place(0.22, [(0, tone(note(88), 0.18, 26)), (0.05, tone(note(81), 0.17, 24))]), 0.5),
    # tab switch: airy swish + soft tone
    'tab': finish(place(0.26, [(0, lowpass(highpass(rng.standard_normal(int(0.24*SR)), 1500), 7000)
                                    * np.sin(np.linspace(0, np.pi, int(0.24*SR))) ** 2 * 0.35),
                               (0.03, tone(note(86), 0.2, 20, 0.6))]), 0.45),
    # launch a game: quick rising arpeggio with shimmer
    'launch': finish(place(0.75, [(0, tone(note(74), 0.6, 7)), (0.07, tone(note(78), 0.6, 7)),
                                  (0.14, tone(note(81), 0.6, 7)), (0.21, tone(note(86), 0.55, 6)),
                                  (0.28, tone(note(98), 0.45, 9, 0.35))]), 0.6),
}
for name, sig in sfx.items():
    write_wav(f'{OUT}/sfx_{name}.wav', sig)
    encode(f'{OUT}/sfx_{name}.wav', f'{OUT}/sfx_{name}.ogg', 5)
print('music seconds:', LEN / SR)

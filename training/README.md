# Wake phrase training

Two Google Colab notebooks that train an [openWakeWord](https://github.com/dscripka/openWakeWord)
keyword model for the shell's on-device wake detection:

- `hey_retinue.ipynb`: “Hey Retinue” (English).
- `svegliati_ara.ipynb`: “Svegliati Ara” (Italian).

Each follows openWakeWord's automatic training recipe: it synthesizes tens of
thousands of spoken examples of the phrase and of similar-sounding phrases,
mixes them with room reverb and background noise, trains against ~2,000 hours
of precomputed negative audio and exports a single `.onnx` file. That file has
the same shape as the stock openWakeWord models (input `[1, 16, 96]`, one
score), which is what `OnnxWakeModels` expects next to the v0.5.1
`melspectrogram.onnx` and `embedding_model.onnx`.

The notebooks are self-contained: they write their helper files (compatibility
patches for current Colab libraries and an adapter for the current
piper-sample-generator) from their own cells, so they need nothing from this
repository.

## Running one

1. Open <https://colab.research.google.com>, then File → Upload notebook and
   pick the `.ipynb`.
2. Runtime → Change runtime type → T4 GPU → Save.
3. Runtime → Run all. It takes about 1–1.5 hours on the free T4 (“Svegliati
   Ara” a bit longer, as its Italian voices run on the CPU). After the clips
   are generated, a cell plays eight samples: listen before training goes on.
4. The last code cell prints the result and downloads `<phrase>.onnx`.

The phrase, the negative phrases and the sizes are in the first code cell. If
the session is interrupted, run all cells again: finished steps are skipped.

## Judging the result

The notebook prints two numbers measured on held-out data:

- **Recall** (share of spoken phrases detected): 0.7 or more is good. The
  synthetic test clips are harder than a real voice near the phone.
- **False positives per hour** (wakes on 11 hours of ordinary speech, music
  and noise): 0.5 or less is good.

## Loading it into the shell

**Shell settings** (the gear next to the app drawer's search field) → **Wake phrase** →
**Models**: **Download feature models** if they are missing, then **Choose
file…** and pick the `.onnx`. Adjust **Sensitivity threshold**: lower wakes
more easily and more often by mistake; 0.5 is openWakeWord's default.

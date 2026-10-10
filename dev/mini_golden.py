# The goldens the native KittenTTS mini engine is tested against: sentences
# through the upstream kittenml package's ONNX backend, with the phonemes
# espeak-ng gives them as phonemizer post-processes them, the token ids the
# model reads, the voice's style row, and the durations the model predicts,
# which, unlike the waveform, are drawn from no noise.
#
#   jolt mini-golden
#
# It writes test/golden/mini/golden.json.

import json
import pathlib
import sys

import os

from kittenml import KittenTTS
from phonemizer.backend.espeak.wrapper import EspeakWrapper

# espeak-ng 1.52.0, which the native engine builds too. The one in the
# espeakng-loader wheel looks for its data where it was built, so on a Mac
# point ESPEAK_LIBRARY at another, Homebrew's say
if os.environ.get("ESPEAK_LIBRARY"):
    # the ONNX backend sets the wheel's when it is imported, so after it
    data = os.environ.get("ESPEAK_DATA_PATH")
    import kittenml.kittentts_legacy.onnx_model  # noqa: F401
    EspeakWrapper.set_library(os.environ["ESPEAK_LIBRARY"])
    EspeakWrapper.set_data_path(data)
    os.environ["ESPEAK_DATA_PATH"] = data

SENTENCES = [
    "Good morning, and welcome to the briefing.",
    "The central bank held rates at four point five percent; markets shrugged.",
    "Why does a strike on an airport tighten a whole shipping corridor?",
    "Houthi attacks on Riyadh's King Khalid International Airport killed three people!",
    "It's a \"half-truce\" on cars, not a deal: watch the tariffs, the quotas, and the fine print.",
    "Kyiv, Tehran, Beijing and Washington all claimed the same week as a win.",
    "Well... maybe not.",
    "Semiconductors, lithium and rare-earth magnets are the new oil.",
]
VOICE = "Hugo"

out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "test/golden/mini")
out.mkdir(parents=True, exist_ok=True)

m = KittenTTS("KittenML/kitten-tts-mini-0.8").model
cases = []
for text in SENTENCES:
    phonemes = m.phonemizer.phonemize([text])[0]
    inputs = m._prepare_inputs(text, VOICE, 1.0)
    waveform, duration = m.session.run(None, inputs)
    cases.append({
        "text": text,
        "phonemes": phonemes,
        "tokens": inputs["input_ids"][0].tolist(),
        "style_row": int(min(len(text), m.voices[m.voice_aliases[VOICE]].shape[0] - 1)),
        "duration": duration.astype(int).tolist(),
        "samples": int(waveform.shape[-1]),
    })
    print(text, "->", phonemes)

json.dump({"voice": VOICE, "cases": cases}, open(out / "golden.json", "w"), indent=1, ensure_ascii=False)

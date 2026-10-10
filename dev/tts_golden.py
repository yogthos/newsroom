# The goldens the native speech engine is tested against: one short utterance
# through the upstream KittenTTS 2 Python package (kittenml), with the
# intermediates of every stage of the S3 decoder recorded, and the noise it
# drew, so the C port can be fed the same noise and compared stage by stage.
#
#   jolt tts-golden
#
# It writes golden.safetensors (tensors) and golden.json (token ids and the
# prompt) to test/golden/tts, read by native/test_s3gen.cpp and
# native/test_tts.cpp (jolt tts-test). Only needed again when the decoder or
# the model changes. More of the stages are recorded than kept, for
# debugging a port: KEEP is what the tests read.

import json
import pathlib
import sys

import numpy as np
import torch
from safetensors.torch import save_file

from kittenml import KittenTTS

TEXT = "Good morning, and welcome to the briefing."
VOICE = "Bruno"
SEED = 1234

args = [a for a in sys.argv[1:] if not a.startswith("--")]
out = pathlib.Path(args[0] if args else "test/golden/tts")
out.mkdir(parents=True, exist_ok=True)

torch.manual_seed(SEED)
m = KittenTTS("KittenML/kitten-tts-2", device="cpu")
codec = m.codec
s3 = codec.s3gen
ref = m._voice_reference(VOICE)
cond = ref["conditioning"]

advanced = m._resolve_advanced({"seed": SEED})
settings = m._resolve_preset("stable", {})
spoken = m.normalize_text(TEXT)
prompt_ids = __import__("kittenml.kittentts2.prompt", fromlist=["x"]).build_generation_prompt(
    m.token_map, m.tokenizer.encode(spoken, add_special_tokens=False),
    reference_prefix=ref["prefix"])
torch.manual_seed(SEED)
audio_ids = m._generate_tokens(ref, spoken, settings, 300, True, None, advanced)
print("generated", len(audio_ids), "codec tokens")

# the first step's logits, to check the native model's prompt and weights
with torch.no_grad():
    # the speaker hook only sees input_ids passed by keyword
    m.model._pending_speaker_embedding = ref["embedding"]
    logits = m.model(input_ids=torch.tensor([prompt_ids])).logits[0, -1].float()
    m.model._pending_speaker_embedding = None
    projected = m.model.spk_proj(ref["embedding"]).float()
top = torch.topk(logits, 64)

rec = {}


def keep(name):
    def hook(_mod, _inp, output):
        o = output[0] if isinstance(output, tuple) else output
        rec[name] = o.detach().float().clone()
    return hook


flow = s3.flow
enc = flow.encoder
est = flow.decoder.estimator
hift = s3.mel2wav
hooks = [
    flow.spk_embed_affine_layer.register_forward_hook(keep("spks")),
    enc.embed.register_forward_hook(keep("enc.embed")),
    enc.pre_lookahead_layer.register_forward_hook(keep("enc.lookahead")),
    enc.encoders[0].register_forward_hook(keep("enc.layer0")),
    enc.encoders[5].register_forward_hook(keep("enc.layer5")),
    enc.up_layer.register_forward_hook(keep("enc.up")),
    enc.up_embed.register_forward_hook(keep("enc.up_embed")),
    enc.up_encoders[3].register_forward_hook(keep("enc.up_layer3")),
    flow.encoder_proj.register_forward_hook(keep("mu")),
    hift.f0_predictor.register_forward_hook(keep("f0")),
    hift.conv_pre.register_forward_hook(keep("hift.conv_pre")),
    hift.ups[0].register_forward_hook(keep("hift.up0")),
    hift.source_downs[0].register_forward_hook(keep("hift.source_down0")),
    hift.source_resblocks[0].register_forward_hook(keep("hift.source_res0")),
    hift.resblocks[0].register_forward_hook(keep("hift.res0")),
    hift.conv_post.register_forward_hook(keep("hift.conv_post")),
]

steps = []
est_parts = {}


# the flow's euler loop calls estimator.forward itself, past any hook
est_forward = est.forward


def est_rec(*args, **kwargs):
    output = est_forward(*args, **kwargs)
    a = dict(kwargs)
    for name, v in zip(["x", "mask", "mu", "t", "spks", "cond", "r"], args):
        a[name] = v
    steps.append({k: (v.detach().float().clone() if torch.is_tensor(v) else v) for k, v in a.items()}
                 | {"out": output.detach().float().clone()})
    return output


est.forward = est_rec
first = {}


def first_only(name):
    def hook(_mod, _inp, output):
        if name not in first:
            o = output[0] if isinstance(output, tuple) else output
            first[name] = o.detach().float().clone()
    return hook


hooks += [
    est.time_mlp.register_forward_hook(first_only("est.time_mlp_t")),
    est.down_blocks[0][0].register_forward_hook(first_only("est.down_resnet")),
    est.down_blocks[0][1][0].register_forward_hook(first_only("est.down_tf0")),
    est.down_blocks[0][2].register_forward_hook(first_only("est.down_sample")),
    est.mid_blocks[0][0].register_forward_hook(first_only("est.mid0_resnet")),
    est.mid_blocks[11][1][3].register_forward_hook(first_only("est.mid11_tf3")),
    est.up_blocks[0][0].register_forward_hook(first_only("est.up_resnet")),
    est.final_block.register_forward_hook(first_only("est.final_block")),
]

# the decoder's noise: the source module's harmonics and noise, and the flow's z
src = {}
orig_sine = hift.m_source.l_sin_gen.forward


def sine_rec(f0):
    w, uv, noise = orig_sine(f0)
    src["sine"] = w.detach().float().clone()
    src["uv"] = uv.detach().float().clone()
    return w, uv, noise


hift.m_source.l_sin_gen.forward = sine_rec
src_out = {}
hooks.append(hift.m_source.register_forward_hook(
    lambda _m, _i, o: src_out.update(merge=o[0].detach().float().clone())))

torch.manual_seed(SEED)
with torch.inference_mode():
    wav = codec.decode(cond, audio_ids)
for h in hooks:
    h.remove()

tensors = {k: v.contiguous() for k, v in rec.items()}
tensors.update({k: v.contiguous() for k, v in first.items()})
for i, s in enumerate(steps):
    for k in ("x", "t", "r", "out"):
        tensors[f"step{i}.{k}"] = s[k].contiguous()
tensors["cond"] = steps[0]["cond"].contiguous()
tensors["source.sine"] = src["sine"].contiguous()
tensors["source.uv"] = src["uv"].contiguous()
tensors["source.merge"] = src_out["merge"].contiguous()
tensors["wav"] = torch.from_numpy(np.asarray(wav, np.float32)).contiguous()
tensors["prompt_token"] = cond["prompt_token"].to(torch.int32).contiguous()
tensors["prompt_feat"] = cond["prompt_feat"].float().contiguous()
tensors["embedding"] = cond["embedding"].float().contiguous()
tensors["speaker"] = ref["embedding"].float().contiguous()
tensors["speaker.projected"] = projected.contiguous()
tensors["logits.top_ids"] = top.indices.to(torch.int32).contiguous()
tensors["logits.top_values"] = top.values.contiguous()
KEEP = {"prompt_token", "prompt_feat", "embedding", "speaker.projected", "step0.x", "step1.x", "step1.out",
        "mu", "f0", "source.merge", "wav", "logits.top_ids", "logits.top_values"}
if "--all" not in sys.argv:
    tensors = {k: v for k, v in tensors.items() if k in KEEP}
save_file(tensors, str(out / "golden.safetensors"))

json.dump({"text": TEXT, "spoken": spoken, "voice": VOICE, "seed": SEED,
           "prompt_ids": prompt_ids, "audio_ids": audio_ids,
           "transcript": ref["transcript"],
           "steps": len(steps)},
          open(out / "golden.json", "w"), indent=1)
for k, v in sorted(tensors.items()):
    print(k, tuple(v.shape))

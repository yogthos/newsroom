#!/usr/bin/env python3
"""Package potion-mxbai-128d-v2 into newsroom resources.

Downloads the model (a static embedding table: WordPiece vocab, per-token
weights, and a 29525x128 int8 matrix) and writes three files under
resources/embed/:

  vocab.txt      one token per line, line number = token id
  weights.edn    EDN vector of per-token weights (floats)
  embeddings.txt the int8 matrix, one char per byte with +256 added to the
                 code point (256-511, so no control chars or newline
                 translation can mangle it), row-major; embedding i for
                 token id t starts at char t*128

The char-per-byte file is UTF-8 on disk (~6.5MB) but needs no parsing:
slurp it and index by char position. Run once; the outputs are committed.

Requires: pip install numpy safetensors
"""
import json
import struct
import sys
import urllib.request
from pathlib import Path

import numpy as np
from safetensors.numpy import load_file

BASE = "https://huggingface.co/blobbybob/potion-mxbai-128d-v2/resolve/main"
FILES = ["config.json", "tokenizer.json", "model.safetensors"]
OUT = Path(__file__).resolve().parent.parent / "resources" / "embed"
DIM = 128


def fetch(tmp: Path, name: str) -> None:
    f = tmp / name
    if not f.exists():
        print("downloading", name)
        urllib.request.urlretrieve(f"{BASE}/{name}", f)


def main() -> None:
    tmp = Path("/tmp/potion")
    tmp.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        fetch(tmp, name)

    tensors = load_file(tmp / "model.safetensors")
    missing = [k for k in ("embeddings", "weights", "mapping") if k not in tensors]
    if missing:
        sys.exit(f"model.safetensors is missing {missing}; not a model2vec checkpoint?")
    emb = tensors["embeddings"]
    weights = tensors["weights"]
    mapping = tensors["mapping"]
    if emb.dtype != np.int8 or emb.shape[1] != DIM:
        sys.exit(f"unexpected embeddings {emb.dtype} {emb.shape}")
    if not np.array_equal(mapping, np.arange(len(mapping))):
        sys.exit("mapping is not identity; the loader would need it")

    vocab = json.load(open(tmp / "tokenizer.json"))["model"]["vocab"]
    if len(vocab) != len(emb):
        sys.exit(f"vocab {len(vocab)} != matrix rows {len(emb)}")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "vocab.txt").write_text("\n".join(vocab) + "\n", encoding="utf-8")
    (OUT / "weights.edn").write_text(
        "[" + " ".join(f"{float(x):.6f}" for x in weights) + "]", encoding="utf-8")
    # each byte as one char in 256-511: raw 0-255 would put \r and \n inside
    # the data, and any text-mode reader would translate them
    (OUT / "embeddings.txt").write_text(
        "".join(chr(int(b) + 256) for b in emb.tobytes()), encoding="utf-8")
    print(f"wrote vocab.txt ({len(vocab)} tokens), weights.edn, embeddings.txt to {OUT}")


if __name__ == "__main__":
    main()

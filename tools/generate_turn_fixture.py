#!/usr/bin/env python3
"""Generate non-speech Smart Turn numerical fixtures; no downloads or audio services.

Install tools/turn-fixture-requirements.txt in a temporary virtual environment first.
The reference features come from Transformers, independently of the Kotlin DSP code.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path

# Keep the reference on Transformers' NumPy implementation even when torch is installed.
os.environ["USE_TORCH"] = "0"

import numpy as np
import onnxruntime as ort
import transformers
from transformers import WhisperFeatureExtractor

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android-runtime/app/src/main/assets"
RESOURCES = ROOT / "android-runtime/app/src/test/resources"
MODEL = ASSETS / "smart-turn-v3.2-cpu.onnx"
RATE = 16_000
SEED = 20_261_014


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def generate() -> dict[Path, bytes]:
    # Five seconds of exact silence exercise the constant-prefix optimization. Three seconds of
    # a swept tone, a fixed tone and seeded broadband noise exercise the FFT and all mel bands.
    # There is no recording, speech synthesis, text prompt, human voice, or downloaded input.
    n = np.arange(3 * RATE, dtype=np.float64)
    t = n / RATE
    rng = np.random.Generator(np.random.PCG64(SEED))
    signal = (
        0.24 * np.sin(2 * np.pi * (190 * t + 170 * t * t))
        + 0.16 * np.sin(2 * np.pi * 910 * t)
        + 0.06 * rng.uniform(-1, 1, len(t))
    )
    signal *= np.sin(np.pi * n / len(t)) ** 2
    pcm = np.zeros(8 * RATE, dtype="<i2")
    pcm[5 * RATE :] = np.rint(signal * 32767).astype("<i2")

    extractor = WhisperFeatureExtractor(chunk_length=8, sampling_rate=RATE)
    features = extractor(
        pcm.astype(np.float32) / 32768,
        sampling_rate=RATE,
        return_tensors="np",
        do_normalize=True,
    ).input_features.astype("<f4")
    assert features.shape == (1, 80, 800)
    assert np.isfinite(features).all()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(MODEL), options, providers=["CPUExecutionProvider"])
    raw = float(session.run(None, {"input_features": features})[0].reshape(-1)[0])
    # Same exported-output interpretation as TurnDetector.probabilityFromFeatures.
    probability = raw if 0 <= raw <= 1 else 1 / (1 + math.exp(-raw))
    pcm_bytes = pcm.tobytes()
    feature_bytes = features.tobytes()
    metadata = {
        "fixture": "non-speech-chirp-tone-noise-v1",
        "sample_rate": RATE,
        "samples": len(pcm),
        "silent_prefix_samples": 5 * RATE,
        "feature_shape": [80, 800],
        "seed": SEED,
        "expected_probability": round(probability, 6),
        "probability_tolerance": 0.01,
        "pcm_sha256": sha256(pcm_bytes),
        "features_sha256": sha256(feature_bytes),
        "model_sha256": sha256(MODEL.read_bytes()),
        "reference": "transformers.WhisperFeatureExtractor NumPy backend, chunk_length=8, do_normalize=True",
        "numpy_version": np.__version__,
        "transformers_version": transformers.__version__,
        "onnxruntime_version": ort.__version__,
    }
    metadata_bytes = (json.dumps(metadata, indent=2, sort_keys=True) + "\n").encode("utf-8")
    return {
        ASSETS / "smartturn_golden.pcm": pcm_bytes,
        ASSETS / "smartturn_golden.json": metadata_bytes,
        RESOURCES / "smartturn_golden.pcm": pcm_bytes,
        RESOURCES / "smartturn_golden.f32": feature_bytes,
        RESOURCES / "smartturn_golden.json": metadata_bytes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify committed fixtures without modifying files")
    args = parser.parse_args()
    outputs = generate()
    different = []
    for path, content in outputs.items():
        if args.check:
            if not path.exists() or path.read_bytes() != content:
                different.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if different:
        print("Fixture mismatch (use the pinned requirements): " + ", ".join(different))
        return 1
    verb = "Verified" if args.check else "Generated"
    print(f"{verb} {len(outputs)} non-speech fixture files; no external audio or model downloads.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

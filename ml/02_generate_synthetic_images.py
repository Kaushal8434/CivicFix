"""
Step 2 - Generative-AI data augmentation.

Uses Stable Diffusion (SD-Turbo, a distilled text-to-image diffusion model)
to synthesise realistic training photos for every CivicFix category.  This
fills the gaps where no public dataset exists (water leakage, drainage,
road blockage, damaged infrastructure) and balances the classes.

Each image is generated from a prompt = subject x style, with a different
random seed, so the model sees varied viewpoints, lighting and weather.
A metadata.csv is written next to the images so every synthetic sample is
traceable to its prompt and seed.

Hardware: an NVIDIA GPU with >= 4 GB VRAM is strongly recommended
(RTX 3050 6 GB: roughly 0.3-0.6 s per 512x512 image).  CPU works but is slow.

Usage:
  python 02_generate_synthetic_images.py                 # uses SYNTH_PER_CLASS
  python 02_generate_synthetic_images.py --scale 0.1     # quick 10% test run
  python 02_generate_synthetic_images.py --only drainage water_leakage
"""
import argparse
import csv
import itertools
import random

import torch

from config import (CATEGORIES, SD_MODEL_ID, SD_NEGATIVE, SD_STYLES,
                    SD_SUBJECTS, SYNTH_DIR, SYNTH_PER_CLASS)


def load_pipeline():
    from diffusers import AutoPipelineForText2Image

    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if device == "cuda" else torch.float32
    print(f"Loading {SD_MODEL_ID} on {device} ...")
    pipe = AutoPipelineForText2Image.from_pretrained(
        SD_MODEL_ID, torch_dtype=dtype, variant="fp16" if device == "cuda" else None
    )
    pipe = pipe.to(device)
    pipe.set_progress_bar_config(disable=True)
    if device == "cuda":
        pipe.enable_attention_slicing()
    return pipe, device


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scale", type=float, default=1.0, help="multiply image counts")
    ap.add_argument("--only", nargs="*", default=None, help="subset of categories")
    ap.add_argument("--steps", type=int, default=2, help="SD-Turbo works with 1-4 steps")
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--seed", type=int, default=1234)
    args = ap.parse_args()

    pipe, device = load_pipeline()
    rng = random.Random(args.seed)
    cats = args.only or CATEGORIES

    for cat in cats:
        out = SYNTH_DIR / cat
        out.mkdir(parents=True, exist_ok=True)
        meta_path = out / "metadata.csv"
        existing = len(list(out.glob("*.png")))
        wanted = int(SYNTH_PER_CLASS[cat] * args.scale)
        if existing >= wanted:
            print(f"[skip] {cat}: {existing} images already")
            continue

        combos = list(itertools.product(SD_SUBJECTS[cat], SD_STYLES))
        new_file = not meta_path.exists()
        with open(meta_path, "a", newline="", encoding="utf-8") as fh:
            writer = csv.writer(fh)
            if new_file:
                writer.writerow(["file", "prompt", "seed", "model", "steps"])
            for i in range(existing, wanted):
                subject, style = rng.choice(combos)
                prompt = f"{subject}, {style}, highly detailed, realistic"
                seed = rng.randrange(2**31)
                gen = torch.Generator(device=device).manual_seed(seed)
                # SD-Turbo is trained without classifier-free guidance
                # (guidance_scale=0.0), so the negative prompt only takes
                # effect when a non-turbo model is configured.
                kwargs = dict(prompt=prompt, num_inference_steps=args.steps,
                              width=args.size, height=args.size, generator=gen)
                if "turbo" in SD_MODEL_ID:
                    kwargs["guidance_scale"] = 0.0
                else:
                    kwargs["negative_prompt"] = SD_NEGATIVE
                img = pipe(**kwargs).images[0]
                name = f"sd_{cat}_{i:05d}.png"
                img.save(out / name)
                writer.writerow([name, prompt, seed, SD_MODEL_ID, args.steps])
                if (i + 1) % 25 == 0:
                    print(f"  {cat}: {i + 1}/{wanted}")
        print(f"[done] {cat}")


if __name__ == "__main__":
    main()

"""Phase 0 QLoRA test job, and the first batch template.

Fine-tunes Qwen2.5-1.5B-Instruct with 4-bit QLoRA on a 1,000-example slice of
alpaca-cleaned. Checkpoints every --save-steps to <work>/checkpoints, resumes
from the newest complete checkpoint with --resume, and writes
<work>/results.json when a run finishes.

--smoke swaps in a tiny model, skips 4-bit and runs on CPU. Everything else,
including checkpointing and resume, is the same code path.
"""

import argparse
import json
import os
import re
import sys
import time
from pathlib import Path

MODEL = "Qwen/Qwen2.5-1.5B-Instruct"
MODEL_REVISION = "989aa7980e4cf806f80c7fef2b1adb7bc71aa306"
SMOKE_MODEL = "trl-internal-testing/tiny-Qwen2ForCausalLM-2.5"
SMOKE_MODEL_REVISION = "ce8d0bf270b28c8fab026ced69fe8aa14b0f0eda"
DATASET = "yahma/alpaca-cleaned"
DATASET_REVISION = "12567cabf869d7c92e573c7c783905fc160e9639"
EXAMPLES = 1000


def parse_args():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--smoke", action="store_true", help="tiny model, no 4-bit, CPU only")
    p.add_argument("--resume", action="store_true", help="continue from the newest checkpoint")
    p.add_argument("--work-dir", default="/work", help="scratch folder (default /work)")
    p.add_argument("--max-steps", type=int, help="optimizer steps (default 100, smoke 200)")
    p.add_argument("--save-steps", type=int, help="checkpoint interval (default 10, smoke 25)")
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()
    if args.max_steps is None:
        args.max_steps = 200 if args.smoke else 100
    if args.save_steps is None:
        args.save_steps = 25 if args.smoke else 10
    return args


def set_cache_dirs(work):
    # The container's root filesystem is read-only, so every cache must live in
    # the scratch folder or /tmp. The model cache goes in scratch so a resumed
    # run does not download the model again.
    os.environ.setdefault("HF_HOME", str(work / "hf-cache"))
    os.environ.setdefault("TRITON_CACHE_DIR", str(work / ".triton"))  # /tmp is noexec
    os.environ.setdefault("TORCHINDUCTOR_CACHE_DIR", "/tmp/torchinductor")
    os.environ.setdefault("CUDA_CACHE_PATH", "/tmp/nv-cache")
    os.environ.setdefault("MPLCONFIGDIR", "/tmp/matplotlib")
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def latest_checkpoint(ckpt_dir):
    """Newest checkpoint that finished writing. A run killed mid-save leaves a
    folder without trainer_state.json, which is skipped."""
    best = None
    for d in ckpt_dir.glob("checkpoint-*"):
        m = re.fullmatch(r"checkpoint-(\d+)", d.name)
        if m and (d / "trainer_state.json").is_file():
            step = int(m.group(1))
            if best is None or step > best[0]:
                best = (step, d)
    return best


def write_json(path, data):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n")
    os.replace(tmp, path)


def to_messages(example):
    prompt = example["instruction"]
    if example["input"]:
        prompt += "\n\n" + example["input"]
    return {"messages": [
        {"role": "user", "content": prompt},
        {"role": "assistant", "content": example["output"]},
    ]}


def main():
    args = parse_args()
    started = time.time()
    work = Path(args.work_dir).resolve()
    ckpt_dir = work / "checkpoints"
    ckpt_dir.mkdir(parents=True, exist_ok=True)
    set_cache_dirs(work)

    resume_from = latest_checkpoint(ckpt_dir) if args.resume else None
    if args.resume and resume_from is None:
        print("--resume given but no complete checkpoint found; starting from step 0")
    if not args.resume and any(ckpt_dir.iterdir()):
        sys.exit(f"{ckpt_dir} already has checkpoints. Pass --resume, or delete the folder to start over.")

    import torch
    from datasets import load_dataset
    from peft import LoraConfig
    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, TrainerCallback
    from trl import SFTConfig, SFTTrainer

    use_gpu = not args.smoke
    if use_gpu and not torch.cuda.is_available():
        sys.exit("No CUDA GPU visible. Run with --gpus, or use --smoke for the CPU test.")

    model_id, revision = (SMOKE_MODEL, SMOKE_MODEL_REVISION) if args.smoke else (MODEL, MODEL_REVISION)
    tokenizer = AutoTokenizer.from_pretrained(model_id, revision=revision)
    if args.smoke:
        model = AutoModelForCausalLM.from_pretrained(model_id, revision=revision, dtype=torch.float32)
    else:
        model = AutoModelForCausalLM.from_pretrained(
            model_id,
            revision=revision,
            quantization_config=BitsAndBytesConfig(
                load_in_4bit=True,
                bnb_4bit_quant_type="nf4",
                bnb_4bit_use_double_quant=True,
                bnb_4bit_compute_dtype=torch.bfloat16,
            ),
            dtype=torch.bfloat16,
            device_map={"": 0},
        )

    dataset = load_dataset(DATASET, split=f"train[:{EXAMPLES}]", revision=DATASET_REVISION)
    dataset = dataset.map(to_messages, remove_columns=dataset.column_names)

    config = SFTConfig(
        output_dir=str(ckpt_dir),
        max_steps=args.max_steps,
        save_steps=args.save_steps,
        save_total_limit=2,
        per_device_train_batch_size=2 if args.smoke else 4,
        gradient_accumulation_steps=1 if args.smoke else 4,
        max_length=128 if args.smoke else 512,
        learning_rate=2e-4,
        lr_scheduler_type="cosine",
        warmup_ratio=0.03,
        logging_steps=5,
        gradient_checkpointing=not args.smoke,
        bf16=not args.smoke,
        use_cpu=args.smoke,
        seed=args.seed,
        report_to="none",
    )
    lora = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        task_type="CAUSAL_LM",
    )

    progress_path = work / "progress.json"
    progress = {"step": resume_from[0] if resume_from else 0,
                "max_steps": args.max_steps,
                "last_checkpoint_step": resume_from[0] if resume_from else 0}
    write_json(progress_path, progress)

    class Progress(TrainerCallback):
        # progress.json lets resume-test.sh see the step count from outside.
        # last_checkpoint_step is written only after a save has finished.
        def __init__(self):
            self.first_step = None

        def on_step_end(self, _args, state, _control, **_kw):
            if self.first_step is None:
                self.first_step = state.global_step
            progress["step"] = state.global_step
            write_json(progress_path, progress)

        def on_save(self, _args, state, _control, **_kw):
            progress["last_checkpoint_step"] = state.global_step
            write_json(progress_path, progress)

    callback = Progress()
    trainer = SFTTrainer(
        model=model,
        args=config,
        train_dataset=dataset,
        processing_class=tokenizer,
        peft_config=lora,
        callbacks=[callback],
    )

    # Count the real (non-padding) tokens this run trains on.
    tokens = {"n": 0}
    collate = trainer.data_collator

    def counting_collator(features):
        batch = collate(features)
        mask = batch.get("attention_mask")
        tokens["n"] += int(mask.sum()) if mask is not None else batch["input_ids"].numel()
        return batch

    trainer.data_collator = counting_collator

    if use_gpu:
        torch.cuda.reset_peak_memory_stats()
    if resume_from:
        print(f"Resuming from {resume_from[1].name}")
    train_started = time.time()
    trainer.train(resume_from_checkpoint=str(resume_from[1]) if resume_from else None)
    train_time = time.time() - train_started

    results = {
        "smoke": args.smoke,
        "model": model_id,
        "examples": EXAMPLES,
        "max_steps": args.max_steps,
        "final_step": trainer.state.global_step,
        "resumed": resume_from is not None,
        "resumed_from_step": resume_from[0] if resume_from else 0,
        "first_step_this_run": callback.first_step,
        "steps_this_run": trainer.state.global_step - (resume_from[0] if resume_from else 0),
        "wall_time_s": round(time.time() - started, 1),
        "train_time_s": round(train_time, 1),
        "tokens_this_run": tokens["n"],
        "tokens_per_second": round(tokens["n"] / train_time, 1) if train_time > 0 else None,
        "peak_vram_mb": round(torch.cuda.max_memory_allocated() / 2**20) if use_gpu else None,
        "gpu": torch.cuda.get_device_name(0) if use_gpu else "cpu",
        "torch": torch.__version__,
    }
    write_json(work / "results.json", results)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()

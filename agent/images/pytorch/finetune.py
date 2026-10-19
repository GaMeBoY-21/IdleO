#!/opt/idleo/venv/bin/python
"""Fine-tune a model (the fine-tune service, docs/site "Writing a job for IdleO").

QLoRA with transformers, peft and bitsandbytes on the renter's own model folder and data file; we never supply a model.

  inputs     SESSION_INPUT_MODEL_DIR  a Hugging Face folder: config.json and safetensors weights
             SESSION_INPUT_DATA_DIR   a JSONL file, each line with a "text" field or chat "messages"
  parameters SESSION_PARAM_EPOCHS, SESSION_PARAM_LEARNING_RATE, SESSION_PARAM_LORA_RANK
  contract   SESSION_OUTPUT_DIR, SESSION_CHECKPOINT_DIR, SESSION_RESUME_DIR, SESSION_CHECKPOINT_INTERVAL, SIGTERM

What's wrong with the inputs is found before anything is loaded, and said in one plain line (exit 2, and the runner ends the
session `invalid_input` with that line). A checkpoint is a folder `checkpoints/<n>/` holding the trainer's own checkpoint,
complete with its optimizer and where it was in the data, then an empty `_DONE`; a resumed attempt carries on from it. On
SIGTERM the next step ends with a last checkpoint and the job exits.

With `--test-cpu` (or IDLEO_TEST_CPU=1), and only when no GPU is visible, it trains plain LoRA on the CPU, for the tests of
the job image itself and of the whole path on a machine without a GPU (and IDLEO_TEST_STEP_DELAY seconds slows each step there,
so a test can stop it part-way). A GPU present means the real thing, whatever is asked.
"""

import json
import math
import os
import shutil
import signal
import sys
import time
from pathlib import Path

LOG_LINE = "epoch {epoch}/{epochs} step {step}/{steps} loss {loss:.4f} lr {lr:g} grad_norm {grad_norm:.3f}"
FAILURE_FILE = os.environ.get("SESSION_FAILURE_FILE", "/work/idleo-failure.json")


def say(text):
    print(text, flush=True)


def invalid_input(message):
    """What the renter gave is wrong: one plain line, to them, and exit 2."""
    say(f"idleo: {message}")
    try:
        Path(FAILURE_FILE).write_text(json.dumps({"code": "invalid_input", "message": message[:200]}))
    except OSError:
        pass
    sys.exit(2)


def env_dir(name):
    value = os.environ.get(name, "")
    return Path(value) if value else None


def find_model_dir(root):
    """The folder with config.json: the input itself, or one folder down if the renter uploaded the folder's parent."""
    if root is None or not root.is_dir():
        invalid_input("No model was given, so there is nothing to fine-tune.")
    for candidate in [root, *sorted(p for p in root.iterdir() if p.is_dir())]:
        if (candidate / "config.json").is_file():
            return candidate
    invalid_input("This folder has no config.json, so it isn't a Hugging Face model.")


def check_model(model_dir):
    try:
        config = json.loads((model_dir / "config.json").read_text())
    except (OSError, ValueError):
        invalid_input("The model's config.json can't be read as JSON, so it isn't a Hugging Face model.")
    if not isinstance(config, dict):
        invalid_input("The model's config.json isn't a JSON object, so it isn't a Hugging Face model.")
    if not any(model_dir.glob("*.safetensors")):
        invalid_input("This model folder has no .safetensors weights; save it with save_pretrained(safe_serialization=True).")


def find_data_file(root):
    if root is None or not root.is_dir():
        invalid_input("No training data was given.")
    files = sorted(p for p in root.rglob("*") if p.is_file() and p.suffix in (".jsonl", ".json", ".ndjson"))
    if not files:
        invalid_input("The training data has no .jsonl file in it; it should be one JSON object a line.")
    return files[0]


def read_examples(path, tokenizer):
    """Each line's training text: its "text" field, or its chat "messages" in the model's chat format."""
    texts = []
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except ValueError:
                invalid_input(f"Line {number} of {path.name} isn't valid JSON.")
            if isinstance(row, dict) and isinstance(row.get("text"), str) and row["text"].strip():
                texts.append(row["text"])
            elif isinstance(row, dict) and isinstance(row.get("messages"), list) and row["messages"]:
                messages = row["messages"]
                if not all(isinstance(m, dict) and isinstance(m.get("role"), str) and isinstance(m.get("content"), str) for m in messages):
                    invalid_input(f"Line {number} of {path.name} has messages without a role and content.")
                if getattr(tokenizer, "chat_template", None):
                    texts.append(tokenizer.apply_chat_template(messages, tokenize=False))
                else:
                    texts.append("\n".join(f"{m['role']}: {m['content']}" for m in messages))
            else:
                invalid_input(f"Line {number} of {path.name} has neither a text field nor messages.")
    if not texts:
        invalid_input(f"{path.name} has no examples in it.")
    return texts


def parameter(name, kind, default, valid=None):
    raw = os.environ.get(f"SESSION_PARAM_{name}", "")
    if raw == "":
        return default
    try:
        value = kind(raw)
    except ValueError:
        invalid_input(f"The {name.lower().replace('_', ' ')} setting, {raw!r}, isn't a number.")
    if valid and not valid(value):
        invalid_input(f"The {name.lower().replace('_', ' ')} setting, {raw!r}, isn't allowed.")
    return value


def main():
    test_cpu = "--test-cpu" in sys.argv or os.environ.get("IDLEO_TEST_CPU") == "1"
    out_dir = Path(os.environ.get("SESSION_OUTPUT_DIR", "/work/outputs"))
    ckpt_dir = Path(os.environ.get("SESSION_CHECKPOINT_DIR", "/work/checkpoints"))
    resume_dir = env_dir("SESSION_RESUME_DIR")
    interval = float(os.environ.get("SESSION_CHECKPOINT_INTERVAL", "300") or 300)
    epochs = parameter("EPOCHS", int, 3, lambda v: 1 <= v <= 20)
    learning_rate = parameter("LEARNING_RATE", float, 2e-4, lambda v: 0 < v < 1)
    rank = parameter("LORA_RANK", int, 16, lambda v: v in (8, 16, 32))

    # The inputs are checked before anything heavy is imported or loaded.
    model_dir = find_model_dir(env_dir("SESSION_INPUT_MODEL_DIR"))
    check_model(model_dir)
    data_file = find_data_file(env_dir("SESSION_INPUT_DATA_DIR"))

    import torch

    on_gpu = torch.cuda.is_available()
    if not on_gpu and not test_cpu:
        say("idleo: no GPU is visible to this job, so it can't fine-tune. This is our fault, not yours.")
        sys.exit(1)
    if on_gpu:
        say(f"Using device: cuda:0 ({torch.cuda.get_device_name(0)})")
    else:
        say("Using device: cpu (a test run)")

    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        BitsAndBytesConfig,
        DataCollatorForLanguageModeling,
        PrinterCallback,
        Trainer,
        TrainerCallback,
        TrainingArguments,
    )

    tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
    tokenizer_changed = False
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        tokenizer_changed = True
    texts = read_examples(data_file, tokenizer)
    max_length = min(1024, int(getattr(tokenizer, "model_max_length", 1024) or 1024))
    if max_length > 100_000:
        max_length = 1024
    encoded = [tokenizer(text, truncation=True, max_length=max_length) for text in texts]

    class Examples(torch.utils.data.Dataset):
        def __len__(self):
            return len(encoded)

        def __getitem__(self, index):
            return {"input_ids": encoded[index]["input_ids"], "attention_mask": encoded[index]["attention_mask"]}

    bf16 = on_gpu and torch.cuda.is_bf16_supported()
    if on_gpu:
        say(f"loading {model_dir.name} in 4-bit")
        quantization = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16 if bf16 else torch.float16,
            bnb_4bit_use_double_quant=True,
        )
        model = AutoModelForCausalLM.from_pretrained(str(model_dir), quantization_config=quantization, device_map={"": 0})
        model = prepare_model_for_kbit_training(model)
    else:
        say(f"loading {model_dir.name} (a test run: no 4-bit)")
        model = AutoModelForCausalLM.from_pretrained(str(model_dir), torch_dtype=torch.float32)
    lora = LoraConfig(r=rank, lora_alpha=2 * rank, lora_dropout=0.05, target_modules="all-linear", task_type="CAUSAL_LM")
    model = get_peft_model(model, lora)

    batch = 4 if on_gpu else 2
    accumulation = 4 if on_gpu else 1
    steps_per_epoch = max(1, math.ceil(len(encoded) / (batch * accumulation)))
    say(f"your model: {sum(1 for p in model_dir.rglob('*') if p.is_file())} files in {model_dir}")
    say(f"your training data: {data_file.name}")
    say(f"{len(encoded)} training examples, {steps_per_epoch} steps an epoch at batch size {batch * accumulation}")
    say(f"LoRA rank {rank}, alpha {2 * rank}, {epochs} epochs, learning rate {learning_rate:g}")

    stop = {"asked": False}
    # Only the tests of this job on a CPU slow each step, so a test can stop it part-way through.
    step_delay = float(os.environ.get("IDLEO_TEST_STEP_DELAY", "0") or 0) if not on_gpu else 0.0

    def on_term(_signal, _frame):
        stop["asked"] = True
        say("asked to stop: saving a checkpoint at the end of this step")

    signal.signal(signal.SIGTERM, on_term)

    work = Path(os.environ.get("HOME", "/work"))
    trainer_dir = work / "trainer"
    out_dir.mkdir(parents=True, exist_ok=True)
    ckpt_dir.mkdir(parents=True, exist_ok=True)
    log_path = out_dir / "training_log.jsonl"
    log_file = log_path.open("a", encoding="utf-8")
    epoch_losses = []

    class Run(TrainerCallback):
        def __init__(self):
            self.last_save = time.monotonic()
            self.saved = 0

        def on_step_end(self, args, state, control, **kwargs):
            if step_delay:
                time.sleep(step_delay)
            if stop["asked"] or time.monotonic() - self.last_save >= interval:
                control.should_save = True
            if stop["asked"]:
                control.should_training_stop = True

        def on_save(self, args, state, control, **kwargs):
            source = trainer_dir / f"checkpoint-{state.global_step}"
            self.saved += 1
            target = ckpt_dir / str(self.saved)
            shutil.copytree(source, target)
            (target / "_DONE").write_text("")
            shutil.rmtree(source, ignore_errors=True)
            self.last_save = time.monotonic()
            say(f"checkpoint {self.saved} at step {state.global_step}")

        def on_log(self, args, state, control, logs=None, **kwargs):
            if not logs or "loss" not in logs:
                return
            epoch = min(epochs, max(1, math.ceil((state.epoch or 0) - 1e-9)))
            epoch_losses.append(float(logs["loss"]))
            record = {
                "epoch": epoch,
                "step": state.global_step,
                "steps": state.max_steps,
                "loss": float(logs["loss"]),
                "lr": float(logs.get("learning_rate", learning_rate)),
                "grad_norm": float(logs.get("grad_norm", 0.0)),
            }
            say(LOG_LINE.format(epochs=epochs, **record))
            log_file.write(json.dumps(record) + "\n")
            log_file.flush()

        def on_epoch_end(self, args, state, control, **kwargs):
            if epoch_losses:
                epoch = min(epochs, max(1, round(state.epoch or 1)))
                say(f"epoch {epoch} done: mean loss {sum(epoch_losses) / len(epoch_losses):.4f}")
                epoch_losses.clear()

    arguments = TrainingArguments(
        output_dir=str(trainer_dir),
        num_train_epochs=epochs,
        learning_rate=learning_rate,
        per_device_train_batch_size=batch,
        gradient_accumulation_steps=accumulation,
        lr_scheduler_type="cosine",
        warmup_steps=0.03,
        logging_steps=5 if on_gpu else 1,
        logging_first_step=True,
        save_strategy="no",
        report_to=[],
        disable_tqdm=True,
        bf16=bf16,
        fp16=on_gpu and not bf16,
        use_cpu=not on_gpu,
        optim="paged_adamw_8bit" if on_gpu else "adamw_torch",
        gradient_checkpointing=on_gpu,
        remove_unused_columns=False,
        dataloader_num_workers=0,
        seed=1234,
    )
    trainer = Trainer(
        model=model,
        args=arguments,
        train_dataset=Examples(),
        data_collator=DataCollatorForLanguageModeling(tokenizer, mlm=False),
        processing_class=tokenizer,
        callbacks=[Run()],
    )

    # Our own lines are the log; the trainer's dictionaries would only repeat them.
    trainer.remove_callback(PrinterCallback)
    resume = None
    if resume_dir is not None:
        if not (resume_dir / "trainer_state.json").is_file():
            say("idleo: the checkpoint to resume from isn't one this job wrote.")
            sys.exit(1)
        resume = str(resume_dir)
        say(f"resuming from {resume_dir}")
    trainer.train(resume_from_checkpoint=resume)

    log_file.close()
    if stop["asked"]:
        say("stopped after saving a checkpoint")
        return
    model.save_pretrained(str(out_dir))
    if tokenizer_changed:
        tokenizer.save_pretrained(str(out_dir))
    say("saving the adapter: adapter_model.safetensors")


if __name__ == "__main__":
    main()

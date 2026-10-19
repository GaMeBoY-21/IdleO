"""Makes a tiny language model and training data for the tests of the job image and of the whole path, with nothing
downloaded: a few-layer Llama with random weights, and a tokenizer trained on a few lines here. Never a real model.

  python make_tiny_model.py <folder>    writes <folder>/model/ (config.json, model.safetensors, tokenizer) and <folder>/data/train.jsonl
"""
import json
import sys
from pathlib import Path

from tokenizers import Tokenizer, models, pre_tokenizers, trainers
from transformers import LlamaConfig, LlamaForCausalLM, PreTrainedTokenizerFast

CORPUS = [
    "the quick brown fox jumps over the lazy dog",
    "a small model learns a small thing from a few lines",
    "hello world this is a test of the training loop",
    "renters bring their own model data and code to a gpu",
    "checkpoints are saved so a job can stop and carry on",
]


def main(folder):
    root = Path(folder)
    model_dir, data_dir = root / "model", root / "data"
    model_dir.mkdir(parents=True, exist_ok=True)
    data_dir.mkdir(parents=True, exist_ok=True)

    tokenizer = Tokenizer(models.BPE(unk_token="<unk>"))
    tokenizer.pre_tokenizer = pre_tokenizers.ByteLevel(add_prefix_space=False)
    tokenizer.train_from_iterator(
        CORPUS * 4,
        trainers.BpeTrainer(vocab_size=300, special_tokens=["<s>", "</s>", "<unk>"], initial_alphabet=pre_tokenizers.ByteLevel.alphabet()),
    )
    fast = PreTrainedTokenizerFast(tokenizer_object=tokenizer, bos_token="<s>", eos_token="</s>", unk_token="<unk>")
    fast.save_pretrained(model_dir)

    config = LlamaConfig(
        vocab_size=len(fast),
        hidden_size=64,
        intermediate_size=128,
        num_hidden_layers=2,
        num_attention_heads=4,
        num_key_value_heads=4,
        max_position_embeddings=256,
        bos_token_id=fast.bos_token_id,
        eos_token_id=fast.eos_token_id,
    )
    LlamaForCausalLM(config).save_pretrained(model_dir, safe_serialization=True)

    with (data_dir / "train.jsonl").open("w") as out:
        for i in range(24):
            if i % 3 == 0:
                out.write(json.dumps({"messages": [{"role": "user", "content": CORPUS[i % 5]}, {"role": "assistant", "content": CORPUS[(i + 1) % 5]}]}) + "\n")
            else:
                out.write(json.dumps({"text": CORPUS[i % 5] + f" number {i}"}) + "\n")
    print(f"wrote a tiny model and 24 examples to {root}")


if __name__ == "__main__":
    main(sys.argv[1])

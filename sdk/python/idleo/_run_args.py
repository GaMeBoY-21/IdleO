"""What `sessions.run(...)`'s arguments mean against a template: which input each name fills, the type of each
parameter, each question's answer, and the size of the GPU. The server checks everything again and its answer is
final; these checks are for saying what's wrong in terms of the arguments, before anything is uploaded (the command
line's are cli/src/run_args.rs)."""

from __future__ import annotations

import json
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from typing import Any

from idleo._files import collect, fingerprint
from idleo._generated.models import Kind, Kind1, TemplateInput, TemplateResponse, Type1
from idleo._problem import UsageError

#: The GPU memory classes renters choose between (ADR 0022); a class asks for its size in thousands of MB.
GPU_CLASSES_GB = (8, 12, 16, 24)

#: What an input can be given as: a link the job fetches, a local file or folder to upload, or an upload already made.
InputValue = str | Path | uuid.UUID


@dataclass(frozen=True)
class Link:
    url: str


@dataclass(frozen=True)
class Upload:
    path: Path


@dataclass(frozen=True)
class Existing:
    upload_id: uuid.UUID


Given = Link | Upload | Existing


def map_inputs(given: Mapping[str, InputValue] | None, template: TemplateResponse) -> dict[str, Given]:
    """Pairs each given input with the template's input it fills.

    Raises UsageError for a name the template doesn't have, a value of the wrong kind, a path that isn't there, or a
    required input left out.
    """
    inputs = {input_.name: input_ for input_ in template.inputs}
    mapped: dict[str, Given] = {}
    for name, value in (given or {}).items():
        if name not in inputs:
            names = ", ".join(inputs) or "none"
            raise UsageError(f"{template.id} has no input {name!r}; its inputs are: {names}")
        mapped[name] = _value_of(inputs[name], value)
    missing = [name for name, input_ in inputs.items() if input_.required and name not in mapped]
    if missing:
        raise UsageError(f"{template.id} needs {' and '.join(missing)}; give each in inputs={{...}}")
    return mapped


def _value_of(input_: TemplateInput, value: InputValue) -> Given:
    name = input_.name
    if isinstance(value, uuid.UUID):
        return Existing(value)
    if isinstance(value, str) and value.startswith("https://"):
        if not input_.link:
            raise UsageError(f"{name} takes files you upload, not a link")
        return Link(value)
    if isinstance(value, str) and value.startswith("http://"):
        raise UsageError(f"{name} must be an https:// link, not http://")
    if not input_.upload:
        raise UsageError(f"{name} takes an https:// link, not files from this computer")
    path = Path(value).expanduser()
    if not path.exists():
        raise UsageError(f"{name}: {str(value)!r} can't be read (no such file or folder)")
    if input_.kind == Kind.folder and not path.is_dir():
        raise UsageError(f"{name} takes a folder, but {str(value)!r} is a file")
    if input_.kind == Kind.file and path.is_dir():
        raise UsageError(f"{name} takes a single file, but {str(value)!r} is a folder")
    return Upload(path)


def map_params(given: Mapping[str, Any] | None, template: TemplateResponse) -> dict[str, str | int | float | bool]:
    """Each parameter, typed as the template says: whole numbers, numbers, true/false, or text."""
    known = {param.name: param for param in template.params}
    out: dict[str, str | int | float | bool] = {}
    for name, value in (given or {}).items():
        if name not in known:
            names = ", ".join(known) or "none"
            raise UsageError(f"{template.id} has no parameter {name!r}; it has: {names}")
        kind = known[name].type
        if kind == Type1.integer:
            if isinstance(value, bool) or not isinstance(value, int | float) or value != int(value):
                raise _bad(name, value, "a whole number")
            out[name] = int(value)
        elif kind == Type1.number:
            if isinstance(value, bool) or not isinstance(value, int | float):
                raise _bad(name, value, "a number")
            out[name] = value
        elif kind == Type1.boolean:
            if not isinstance(value, bool):
                raise _bad(name, value, "true or false")
            out[name] = value
        else:
            if isinstance(value, bool) or not isinstance(value, str | int | float):
                raise _bad(name, value, "text")
            out[name] = str(value)
    return out


def _bad(name: str, value: object, wanted: str) -> UsageError:
    return UsageError(f"parameter {name}={value!r} should be {wanted}")


def map_presets(given: Mapping[str, str] | None, template: TemplateResponse) -> dict[str, str]:
    """The answer to every preset question: the one given, else the question's usual one."""
    asked = [q for q in template.questions if q.kind == Kind1.preset and q.name]
    by_name = {q.name: q for q in asked if q.name}
    chosen: dict[str, str] = {}
    for name, option in (given or {}).items():
        if name not in by_name:
            names = ", ".join(by_name) or "nothing"
            raise UsageError(f"{template.id} doesn't ask {name!r}; it asks: {names}")
        options = [o.id for o in by_name[name].options]
        if option not in options:
            raise UsageError(f"{name} can be {' or '.join(options)}, not {option!r}")
        chosen[name] = option
    for name, question in by_name.items():
        if question.default_option:
            chosen.setdefault(name, question.default_option)
    return chosen


def vram_mb(gpu_memory_gb: int | None, template: TemplateResponse) -> int | None:
    """What `gpu_memory_gb=12` asks for, in MB: a class's size in thousands of MB, at least what the template needs."""
    if gpu_memory_gb is None:
        return None
    if gpu_memory_gb not in GPU_CLASSES_GB:
        raise UsageError(f"GPU memory comes in 8, 12, 16 or 24 GB; {gpu_memory_gb!r} isn't one of them")
    return max(gpu_memory_gb * 1_000, template.min_vram_mb)


def spend(max_spend_paise: int | None, template: TemplateResponse) -> int:
    """The most the session may cost, in paise: the argument, else the template's usual budget."""
    amount = max_spend_paise if max_spend_paise is not None else template.default_budget_paise
    if amount is None:
        raise UsageError(f"{template.id} has no usual budget; give one with max_spend_paise=PAISE")
    if isinstance(amount, bool) or not isinstance(amount, int) or amount < 1:
        raise UsageError("max_spend_paise is a whole number of paise, at least 1 (200_00 is ₹200)")
    return amount


def runtime_seconds(max_runtime: int | timedelta | None) -> int | None:
    """The longest the session may run, in whole seconds."""
    if max_runtime is None:
        return None
    seconds = int(max_runtime.total_seconds()) if isinstance(max_runtime, timedelta) else max_runtime
    if isinstance(seconds, bool) or not isinstance(seconds, int) or seconds < 1:
        raise UsageError("max_runtime is seconds (or a timedelta) of at least 1")
    return seconds


def asked_for(
    template: str,
    given: Mapping[str, Given],
    params: Mapping[str, str | int | float | bool],
    presets: Mapping[str, str],
    spend_paise: int,
    runtime: int | None,
    vram: int | None,
) -> str:
    """What the renter asked for, as text: the template, each input (a file or folder by its place on this computer
    and its files' paths, sizes and modification times, a link by its URL, an upload by its id), the parameters, the
    answers and the limits. The booking's Idempotency-Key comes from this, never from upload ids a re-run could change,
    so the same call after a lost answer finds the same key and never books twice (the command line does the same)."""
    inputs: dict[str, dict[str, str]] = {}
    for name, value in given.items():
        if isinstance(value, Link):
            inputs[name] = {"url": value.url}
        elif isinstance(value, Existing):
            inputs[name] = {"uploadId": str(value.upload_id)}
        else:
            root, files = collect(value.path)
            inputs[name] = {"path": str(value.path.resolve()), "files": fingerprint(root, files)}
    return json.dumps(
        {
            "template": template,
            "inputs": inputs,
            "params": dict(params),
            "presets": dict(presets),
            "maxSpendPaise": spend_paise,
            "maxRuntimeSeconds": runtime,
            "vramNeededMb": vram,
        },
        sort_keys=True,
    )

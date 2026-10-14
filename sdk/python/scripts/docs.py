"""Makes the SDK's reference page for the public docs from the code's own docstrings, so it can't drift from it.

    uv run python scripts/docs.py           rewrite docs/site/generated/sdk.md
    uv run python scripts/docs.py --check   fail if the committed page differs (tests/test_docs.py does the same)

The public site's build (web/scripts/build-site.ts) renders that file as the SDK page, as it does the command line's.
"""

from __future__ import annotations

import argparse
import inspect
import json
import sys
from pathlib import Path

from idleo import Client, Session, errors, money
from idleo._brand import PRODUCT
from idleo._problem import exit_code_for
from idleo.client import Sessions, Templates, Uploads
from idleo.outputs import Outputs

ROOT = Path(__file__).resolve().parents[3]
PAGE = ROOT / "docs" / "site" / "generated" / "sdk.md"

INTRO = f"""\
{PRODUCT}'s Python SDK: run, follow and download sessions from a script or a notebook. It works like the command line: \
the same sign-in, the same uploads that carry on after a failure, and the same meanings for what goes wrong.

## Install and sign in

From a copy of the repository: `pip install ./sdk/python` (or `uv add ./sdk/python`). It needs Python 3.10 or newer.

`Client()` finds your API key in this order: the `api_key` argument, the `IDLEO_API_KEY` environment variable, \
then the key `idleo login` stored on this computer. So after `idleo login` there is nothing to set. The API's \
address is the `api_url` argument, else `IDLEO_API_URL`.

## Run a session

```python
from idleo import Client

client = Client()
s = client.sessions.run(
    "fine-tune-simulated",
    inputs={{"model": "./my-model", "data": "./train.jsonl"}},
    presets={{"thoroughness": "standard"}},
    max_spend_paise=200_00,
)
for line in s.logs(follow=True):
    print(line)
s.wait()
s.outputs.download("./out")
```

You bring the model and the data; we provide the GPU. Each input is a folder or file on your computer (uploaded first, \
with its paths kept), an https link the job fetches itself, or the id of an upload you made before. The names of a \
service's inputs, presets and parameters are in the services page, or `client.templates.list()`.

Money is whole paise everywhere: `max_spend_paise=200_00` is ₹200. `idleo.rupees(paise)` writes an amount for people.

In a Jupyter notebook, `wait()`, `logs(follow=True)` and uploads show their progress as a line that updates in place; \
anywhere else they are silent unless you pass `progress=True`.
"""


def signature(member: object) -> str:
    """The call as written in the source: no quotes round annotations, no `self`, no module prefixes."""
    text = str(inspect.signature(member))  # type: ignore[arg-type]
    text = text.replace("(self, ", "(").replace("(self)", "()").replace("'", "")
    return text.replace("builtins.", "").replace("args.InputValue", "str | Path | UUID")


def document(title: str, prefix: str, cls: type, names: list[str]) -> list[str]:
    out = [f"## `{title}`", "", inspect.getdoc(cls) or "", ""]
    for name in names:
        member = inspect.getattr_static(cls, name)
        if isinstance(member, property):
            out += [f"### `{prefix}.{name}`", "", inspect.getdoc(member.fget) or "", ""]
            continue
        out += [
            f"### `{prefix}.{name}{signature(member)}`",
            "",
            inspect.getdoc(member) or "",
            "",
        ]
    return [line for line in out]


def errors_table() -> list[str]:
    catalog = json.loads((ROOT / "api" / "problems.json").read_text())
    out = [
        "## Exceptions",
        "",
        "Everything the SDK raises on purpose is an `idleo.errors.IdleoError`, and each class says what the "
        "command line would exit with (`exit_code`). The API's problems have one class each, named from the problem "
        "type (`insufficient-credit` is `InsufficientCredit`); a 429 carries `retry_after`, and a 400 about "
        "invalid fields carries `fields`.",
        "",
        "| Class | Problem type | HTTP | Exit code |",
        "| --- | --- | --- | --- |",
    ]
    for entry in catalog:
        cls = getattr(errors, "".join(part.capitalize() for part in entry["slug"].split("-")))
        out.append(f"| `{cls.__name__}` | `{entry['slug']}` | {entry['status']} | {exit_code_for(entry['slug'])} |")
    out += [
        "",
        "And the SDK's own: `NotSignedIn` (no key found; 5), `NetworkError` (7), `UsageError` (the arguments don't fit "
        "the template, found before anything is uploaded; 2), `ServerError`, `SessionNotCompleted` (carries the "
        "session) and `WaitTimeout`.",
        "",
    ]
    return out


def page() -> str:
    lines = INTRO.splitlines()
    lines.append("")
    lines += document("Client", "client", Client, ["me", "credit", "availability", "close"])
    lines += document("client.templates", "client.templates", Templates, ["list", "get"])
    lines += document("client.uploads", "client.uploads", Uploads, ["upload"])
    lines += document("client.sessions", "client.sessions", Sessions, ["run", "create", "get", "list", "iter"])
    lines += document(
        "Session",
        "session",
        Session,
        [
            "id",
            "state",
            "settled",
            "charged_paise",
            "cost_paise",
            "max_spend_paise",
            "error_code",
            "gpu",
            "started_at",
            "ended_at",
            "refresh",
            "events",
            "wait",
            "logs",
            "stop",
            "cancel",
            "outputs",
        ],
    )
    lines += document("Outputs", "session.outputs", Outputs, ["list", "download"])
    lines += ["## Money", ""]
    for name in ("rupees", "paise"):
        function = getattr(money, name)
        lines += [f"### `idleo.{name}{signature(function)}`", "", inspect.getdoc(function) or "", ""]
    lines += errors_table()
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    made = page()
    if args.check:
        if PAGE.exists() and PAGE.read_text() == made:
            print("The SDK reference matches the code.")
            return 0
        print(f"{PAGE} differs from the code's docstrings. Run: uv run python scripts/docs.py")
        return 1
    PAGE.parent.mkdir(parents=True, exist_ok=True)
    PAGE.write_text(made)
    return 0


if __name__ == "__main__":
    sys.exit(main())

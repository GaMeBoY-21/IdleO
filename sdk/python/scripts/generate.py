"""Makes the SDK's generated layer from the API's contract, and checks it hasn't drifted.

    uv run python scripts/generate.py           rewrite idleo/_generated/
    uv run python scripts/generate.py --check   fail if what's committed differs from what the contract makes

The models come from ../../api/openapi.yaml (without /admin/v1, which no client of ours calls); the exception classes
come from ../../api/problems.json, the catalog the clients share (docs/api/problems.md).
"""

from __future__ import annotations

import argparse
import difflib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent.parent
ROOT = HERE.parent.parent
OUT = HERE / "idleo" / "_generated"
HEADER = "# Generated from {source} by scripts/generate.py. Do not edit; run it again after the contract changes.\n"


def public_spec() -> dict:
    """The contract without the admin console's calls, and only the schemas the rest still reaches."""
    spec = yaml.safe_load((ROOT / "api" / "openapi.yaml").read_text())
    spec["paths"] = {path: item for path, item in spec["paths"].items() if not path.startswith("/admin/")}
    schemas = spec["components"]["schemas"]
    wanted: set[str] = set()

    def walk(node: object) -> None:
        if isinstance(node, dict):
            ref = node.get("$ref")
            if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
                name = ref.rsplit("/", 1)[1]
                if name not in wanted:
                    wanted.add(name)
                    walk(schemas[name])
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(spec["paths"])
    spec["components"]["schemas"] = {name: schemas[name] for name in sorted(wanted)}
    return spec


def models() -> str:
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "openapi.yaml"
        target = Path(tmp) / "models.py"
        source.write_text(yaml.safe_dump(public_spec(), sort_keys=True))
        subprocess.run(
            [
                sys.executable,
                "-m",
                "datamodel_code_generator",
                "--input",
                str(source),
                "--input-file-type",
                "openapi",
                "--output",
                str(target),
                "--output-model-type",
                "pydantic_v2.BaseModel",
                "--target-python-version",
                "3.10",
                "--use-annotated",
                "--use-union-operator",
                "--snake-case-field",
                "--allow-population-by-field-name",
                "--disable-timestamp",
                "--use-standard-collections",
                "--field-constraints",
            ],
            check=True,
        )
        text = target.read_text()
    return HEADER.format(source="api/openapi.yaml") + text


def class_name(slug: str) -> str:
    return "".join(part.capitalize() for part in slug.split("-"))


def errors() -> str:
    """One class for each problem type, named from its slug, in a table from slug to class."""
    catalog = json.loads((ROOT / "api" / "problems.json").read_text())
    lines = [
        HEADER.format(source="api/problems.json").rstrip(),
        '"""One exception class for each problem type in the API\'s catalog (docs/api/problems.md)."""',
        "",
        "from __future__ import annotations",
        "",
        "from idleo._problem import IdleoError",
        "",
    ]
    for entry in catalog:
        name = class_name(entry["slug"])
        lines += [
            "",
            f"class {name}(IdleoError):",
            f'    """{entry["title"]} (HTTP {entry["status"]})."""',
            "",
            f'    slug = "{entry["slug"]}"',
            f"    status = {entry['status']}",
            f'    title = "{entry["title"]}"',
            "",
        ]
    lines += ["", "__all__ = ["]
    lines += [f'    "{class_name(entry["slug"])}",' for entry in catalog]
    lines += ["]", "", "PROBLEMS: dict[str, type[IdleoError]] = {"]
    lines += [f'    "{entry["slug"]}": {class_name(entry["slug"])},' for entry in catalog]
    lines += ["}", ""]
    return "\n".join(lines)


def files() -> dict[Path, str]:
    return {OUT / "models.py": models(), OUT / "errors.py": errors()}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    made = files()
    if not args.check:
        OUT.mkdir(exist_ok=True)
        for path, text in made.items():
            path.write_text(text)
            subprocess.run([sys.executable, "-m", "ruff", "format", "--quiet", "--no-cache", str(path)], check=True)
        return 0
    drifted = False
    with tempfile.TemporaryDirectory() as tmp:
        for path, text in made.items():
            scratch = Path(tmp) / path.name
            scratch.write_text(text)
            subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "ruff",
                    "format",
                    "--quiet",
                    "--no-cache",
                    "--config",
                    str(HERE / "pyproject.toml"),
                    str(scratch),
                ],
                check=True,
            )
            wanted = scratch.read_text()
            have = path.read_text() if path.exists() else ""
            if have != wanted:
                drifted = True
                sys.stdout.writelines(
                    difflib.unified_diff(have.splitlines(True), wanted.splitlines(True), str(path), "generated", n=2)
                )
    if drifted:
        print("\nThe generated SDK layer differs from the contract. Run: uv run python scripts/generate.py")
        return 1
    print("The generated SDK layer matches the contract.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

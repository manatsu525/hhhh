#!/usr/bin/env python3
"""Sanity-check a standalone VCMI mod directory.

Checks JSON syntax, duplicate object IDs/keys, and obvious missing local file
references in JSON string values. This is intentionally conservative: VCMI's
full schema varies by version, so use this as a preflight check rather than a
replacement for VCMI's own loader.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

FILE_EXTENSIONS = {
    ".bmp", ".def", ".json", ".mp3", ".ogg", ".png", ".wav", ".webm", ".txt",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate a VCMI mod directory")
    parser.add_argument("mod_dir", type=Path, help="Path to the generated mod directory")
    parser.add_argument(
        "--reference-root",
        action="append",
        type=Path,
        default=[],
        help="Additional root to search for referenced files (may be repeated)",
    )
    return parser.parse_args()


def object_pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    keys = [key for key, _ in pairs]
    duplicates = [key for key, count in Counter(keys).items() if count > 1]
    if duplicates:
        raise ValueError(f"duplicate JSON keys: {', '.join(sorted(duplicates))}")
    return dict(pairs)


def iter_json_files(root: Path) -> Iterable[Path]:
    yield from sorted(path for path in root.rglob("*.json") if path.is_file())


def collect_ids(value: Any, ids: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in {"id", "identifier"} and isinstance(child, str):
                ids.append(child)
            collect_ids(child, ids)
    elif isinstance(value, list):
        for child in value:
            collect_ids(child, ids)


def iter_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from iter_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_strings(child)


def looks_like_file_reference(text: str) -> bool:
    lowered = text.lower().split("?", 1)[0].split("#", 1)[0]
    return any(lowered.endswith(ext) for ext in FILE_EXTENSIONS) and "://" not in lowered


def exists_in_roots(reference: str, json_file: Path, roots: list[Path]) -> bool:
    normalized = reference.replace("\\", "/").lstrip("/")
    candidates = [json_file.parent / normalized]
    candidates.extend(root / normalized for root in roots)
    return any(candidate.exists() for candidate in candidates)


def main() -> int:
    args = parse_args()
    mod_dir = args.mod_dir.resolve()
    roots = [mod_dir, *(root.resolve() for root in args.reference_root)]
    errors: list[str] = []
    ids: list[str] = []

    if not mod_dir.is_dir():
        errors.append(f"mod directory does not exist: {mod_dir}")
    elif not (mod_dir / "mod.json").is_file():
        errors.append(f"missing required mod.json at: {mod_dir / 'mod.json'}")

    for json_file in iter_json_files(mod_dir) if mod_dir.is_dir() else []:
        try:
            data = json.loads(json_file.read_text(encoding="utf-8"), object_pairs_hook=object_pairs_hook)
        except Exception as exc:  # noqa: BLE001 - report exact parse/duplicate-key error
            errors.append(f"{json_file}: invalid JSON: {exc}")
            continue

        collect_ids(data, ids)
        for text in iter_strings(data):
            if looks_like_file_reference(text) and not exists_in_roots(text, json_file, roots):
                errors.append(f"{json_file}: missing referenced file: {text}")

    for object_id, count in Counter(ids).items():
        if count > 1:
            errors.append(f"duplicate object id inside mod: {object_id} ({count} occurrences)")

    if errors:
        print("VCMI mod validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"VCMI mod validation passed: {mod_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

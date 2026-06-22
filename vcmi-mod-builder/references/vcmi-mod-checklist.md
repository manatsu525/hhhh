# VCMI Mod Checklist

Use this checklist when creating a standalone VCMI mod.

## Layout

- `mod.json` exists at the mod root.
- Content is under the mod directory, commonly `config/` and subdirectories such as `creatures/`, `spells/`, `artifacts/`, or `skills/` depending on local examples.
- Archive root contains the mod folder or extracts to a folder containing `mod.json`; avoid nested duplicate folders.

## `mod.json`

Include only fields supported by the target VCMI version. Common fields in VCMI mods include metadata (`name`, `description`, `version`, `author`, `contact`, `homepage`) and dependencies. Confirm exact spelling and structure from local examples or current VCMI docs/source.

## IDs and dependencies

- Use a stable namespace/prefix based on the mod name.
- Avoid collisions with base game and local mod IDs.
- Declare dependencies for external objects referenced from another mod.
- Do not declare HOTA unless requested.

## Validation

- Parse every JSON file.
- Detect duplicate object IDs inside generated JSON objects.
- Verify referenced local files exist.
- Check that zip contents do not include `.git`, caches, or unrelated project files.

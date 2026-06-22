---
name: vcmi-mod-builder
description: Create standalone, Android-installable VCMI mods from natural-language requirements. Use when Codex needs to inspect a VCMI project or data tree, create a new independent VCMI mod with mod.json plus config/creatures/spells/artifacts/skills JSON files, validate IDs and dependencies, package the mod as a zip, and explain Android installation without modifying base game files.
---

# VCMI Mod Builder

Build complete VCMI mods as independent add-ons, especially for Android installation. Do not modify original game or core VCMI files; place all generated content under a new mod directory.

## Required workflow

1. Inspect the current project for VCMI mod/data layout before writing files.
   - Look for existing `mod.json`, `Mods/`, `config/`, `Content/`, `creatures/`, `spells/`, `artifacts/`, `skills/`, and `*.json` files.
   - Identify whether the working tree is a VCMI source checkout, a mod collection, or a small standalone repo.
2. Find reference JSON definitions for any requested content type.
   - Search existing creatures, spells, artifacts, skills, factions, bonuses, animations, sounds, and dependencies as relevant.
   - Prefer base VCMI/H3-compatible examples. Do not depend on Horn of the Abyss/HOTA unless the user explicitly asks for HOTA content or compatibility.
3. Confirm currently supported VCMI fields before modifying files.
   - Check local docs/schemas/examples first. If absent or likely stale, verify against the VCMI version in the project or official VCMI documentation/source.
   - Note any fields inferred from examples versus confirmed from docs/source.
4. Create a complete standalone mod.
   - Include `mod.json` with name, description, version, author if known, contact/homepage if provided, compatibility info when known, and explicit dependencies.
   - Add only the needed JSON content directories such as `config/`, `config/creatures/`, `config/spells/`, `config/artifacts/`, or `config/skills/`, matching the discovered project convention.
   - Keep object IDs unique within the mod and avoid collisions with existing project content.
   - Include required assets or placeholders only if the target format supports them; otherwise document missing art/sound requirements.
5. Validate before packaging.
   - Run JSON syntax checks.
   - Check paths referenced by JSON files.
   - Check duplicate object IDs within the mod and obvious collisions with local references.
   - Check dependency declarations match referenced external objects.
   - Use `scripts/validate_mod.py` from this skill when helpful; copy or run it against the generated mod directory.
6. Package the final mod as a zip.
   - Zip the mod folder so `mod.json` is at the root of the archive after extraction.
   - Do not include unrelated repository files, `.git`, caches, or validation logs unless the user asks.
7. Explain Android installation.
   - Tell the user to copy the zip or extracted mod folder to the VCMI Android mods directory. Common locations vary by Android version and VCMI build, so phrase as: `Android/data/is.xyz.vcmi/files/Mods/` or the app-specific `files/Mods/` folder visible through the Android file picker/VCMI launcher.
   - Tell the user to enable the mod in the VCMI launcher and restart if required.

## Safety rules

- Never edit original VCMI game data or third-party mod files to implement a new request.
- Do not add HOTA as a dependency unless explicitly requested.
- If a requested mechanic is unsupported by the confirmed VCMI version, explain the limitation and choose the closest supported implementation only after noting the tradeoff.
- Preserve existing user changes in the repository.

## Bundled resources

- `scripts/validate_mod.py`: deterministic JSON/path/ID sanity checker for a generated mod directory.
- `references/vcmi-mod-checklist.md`: concise checklist for common VCMI mod files and validation points.

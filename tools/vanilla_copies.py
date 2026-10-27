#!/usr/bin/env python3
"""Gives Diffusicraft worlds their own copies of the vanilla terrain data they build on, so that mods and data packs
which replace vanilla's files (Terralith, Tectonic and the like) change vanilla worlds, never Diffusicraft's.

Both generators run this after writing their files; it can also run alone:

    python3 tools/vanilla_copies.py

Every reference that Diffusicraft's noise settings, density functions and material rules make to a vanilla density
function, material rule or material condition is rewritten to a copy under diffusicraft:minecraft/, and what the copies
reference is copied in turn. Copies nothing references any more are deleted. A diffusicraft:other_mods rule keeps its
references: it reads vanilla's rule as other mods leave it, on purpose, for their biomes.

Noises keep their vanilla names, as a noise's name seeds it: every vanilla noise is copied, and in Diffusicraft worlds
RandomStateMixin looks a vanilla noise up under diffusicraft:minecraft/. The overworld's carvers are copied too, and
NoiseBasedChunkGeneratorMixin carves with the copies. See docs/WORLDGEN.md.
"""
import json
import sys
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
ROOT = PROJECT / "src" / "main" / "resources" / "data"
NS = "diffusicraft"
COPIES = "minecraft"
# The folders whose references are rewritten, and the kinds copied by reference.
OURS = ("noise_settings", "density_function", "material_rule")
REFERENCED = ("density_function", "material_rule", "material_condition")
# Copied whole.
NOISES = "noise"
CARVERS = ("cave", "cave_extra_underground", "canyon")
# Keys that name material rules or material conditions; other strings, but for the keys that name noises, blocks,
# biomes and random sequences, name density functions.
RULE_KEYS = {"sequence", "then_run", "surface", "rule", "material_rule"}
CONDITION_KEYS = {"if_true", "invert"}
OTHER_KEYS = {"type", "noise", "random_name", "result_state", "Name", "biome_is", "default_block", "default_fluid"}
OTHER_MODS = f"{NS}:other_mods"


def jar():
    props = dict(line.split("=", 1) for line in (PROJECT / "gradle.properties").read_text().splitlines()
                 if "=" in line and not line.startswith("#"))
    return Path.home() / ".gradle" / "caches" / "fabric-loom" / props["minecraft_version"].strip() / \
        "minecraft-common.jar"


def vanilla(path):
    """Vanilla's world generation data, by (folder, path)."""
    data = {}
    prefix = "data/minecraft/worldgen/"
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.startswith(prefix) and name.endswith(".json"):
                folder, file = name[len(prefix):-5].split("/", 1)
                data[(folder, file)] = json.loads(z.read(name))
    return data


def kind(key):
    if key in OTHER_KEYS:
        return None
    if key in RULE_KEYS:
        return "material_rule"
    if key in CONDITION_KEYS:
        return "material_condition"
    return "density_function"


def rewrite(node, key, data, found):
    """`node` with its references to vanilla's density functions, rules and conditions pointed at the copies, which go
    into `found`."""
    if isinstance(node, dict):
        if node.get("type") == OTHER_MODS:
            return node
        return {k: rewrite(v, k, data, found) for k, v in node.items()}
    if isinstance(node, list):
        return [rewrite(v, key, data, found) for v in node]
    if isinstance(node, str):
        # A file rewritten before (biomes.py runs this over files worldgen_data.py wrote) still needs its copies.
        path = node.removeprefix(f"{NS}:{COPIES}/") if node.startswith(f"{NS}:{COPIES}/") else \
            node.removeprefix("minecraft:") if node.startswith("minecraft:") else None
        target = (kind(key), path)
        if path is not None and target in data:
            found.add(target)
            return f"{NS}:{COPIES}/{path}"
    return node


def dumps(data):
    return json.dumps(data, indent=2) + "\n"


def update(jar_path=None):
    data = vanilla(jar_path or jar())
    found = set()
    for folder in OURS:
        base = ROOT / NS / "worldgen" / folder
        for file in sorted(base.rglob("*.json")):
            if file.relative_to(base).parts[0] == COPIES:
                continue
            text = file.read_text()
            new = dumps(rewrite(json.loads(text), None, data, found))
            if new != text:
                file.write_text(new)
    copies = {}
    while found:
        target = found.pop()
        if target not in copies:
            copies[target] = rewrite(data[target], None, data, found)
    for (folder, path), value in data.items():
        if folder == NOISES or folder == "carver" and path in CARVERS:
            copies[(folder, path)] = value
    for folder in REFERENCED + (NOISES, "carver"):
        base = ROOT / NS / "worldgen" / folder / COPIES
        keep = {base / f"{path}.json" for f, path in copies if f == folder}
        for file in sorted(base.rglob("*.json")) if base.exists() else []:
            if file not in keep:
                file.unlink()
        for directory in sorted(base.rglob("*"), reverse=True) if base.exists() else []:
            if directory.is_dir() and not any(directory.iterdir()):
                directory.rmdir()
    for (folder, path), value in sorted(copies.items()):
        file = ROOT / NS / "worldgen" / folder / COPIES / f"{path}.json"
        file.parent.mkdir(parents=True, exist_ok=True)
        if not file.exists() or file.read_text() != dumps(value):
            file.write_text(dumps(value))


if __name__ == "__main__":
    update(Path(sys.argv[1]) if len(sys.argv) > 1 else None)

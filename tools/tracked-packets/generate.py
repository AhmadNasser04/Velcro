#!/usr/bin/env python3
"""Generates the per-version tracked packet ID tables for seamless server switches.

For every protocol version listed in VERSIONS, this script downloads the matching vanilla
server jar from Mojang's version manifest, runs the built-in data generator to produce the
official packets.json report, and extracts the clientbound PLAY packet IDs that
com.velocitypowered.proxy.protocol.tracking needs. The result is written to
proxy/src/main/java/com/velocitypowered/proxy/protocol/tracking/TrackedPacketTables.java.

Adding support for a new Minecraft version is a one-line change: append
(velocity enum name, protocol number, minecraft version) to VERSIONS and rerun this script.
The Minecraft version should be the newest release using that protocol number.

Downloads and generated reports are cached in ~/.cache/velcro-packet-reports, so reruns
only fetch versions that were not seen before.

Usage: python3 tools/tracked-packets/generate.py
Requires: Java 21+ on the PATH (the data generator runs the actual server code).
"""

import json
import pathlib
import subprocess
import sys
import urllib.request

VERSIONS = [
    # (Velocity ProtocolVersion enum constant, protocol number, Minecraft version)
    ("MINECRAFT_1_20_5", 766, "1.20.6"),
    ("MINECRAFT_1_21", 767, "1.21.1"),
    ("MINECRAFT_1_21_2", 768, "1.21.3"),
    ("MINECRAFT_1_21_4", 769, "1.21.4"),
    ("MINECRAFT_1_21_5", 770, "1.21.5"),
    ("MINECRAFT_1_21_6", 771, "1.21.6"),
    ("MINECRAFT_1_21_7", 772, "1.21.8"),
    ("MINECRAFT_1_21_9", 773, "1.21.10"),
    ("MINECRAFT_1_21_11", 774, "1.21.11"),
    ("MINECRAFT_26_1", 775, "26.1.2"),
    ("MINECRAFT_26_2", 776, "26.2"),
]

# The clientbound PLAY packets the proxy tracks, in TrackedPackets constructor order,
# by their vanilla resource names as they appear in packets.json.
TRACKED_CLIENTBOUND = [
    "minecraft:add_entity",
    "minecraft:add_experience_orb",
    "minecraft:remove_entities",
    "minecraft:update_mob_effect",
    "minecraft:remove_mob_effect",
    "minecraft:level_chunk_with_light",
    "minecraft:forget_level_chunk",
    "minecraft:set_chunk_cache_center",
    "minecraft:set_chunk_cache_radius",
    "minecraft:update_recipes",
    "minecraft:update_tags",
    "minecraft:set_objective",
    "minecraft:set_player_team",
    "minecraft:player_position",
]

# Serverbound PLAY packets, appended after the clientbound IDs in constructor order.
TRACKED_SERVERBOUND = [
    "minecraft:chat_session_update",
]

# Packets that only exist in some of the supported versions; absent ones are emitted as -1.
# add_experience_orb was folded into add_entity in 1.21.5.
OPTIONAL = {
    "minecraft:add_experience_orb",
}

# The same packets under minecraft-data's names, used for versions that predate the
# vanilla packets.json report (added during the 1.21.x cycle).
MCDATA_NAMES = [
    "spawn_entity",
    "spawn_entity_experience_orb",
    "entity_destroy",
    "entity_effect",
    "remove_entity_effect",
    "map_chunk",
    "unload_chunk",
    "update_view_position",
    "update_view_distance",
    "declare_recipes",
    "tags",
    "scoreboard_objective",
    "teams",
    "position",
]

MCDATA_SERVERBOUND = [
    "chat_session_update",
]

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
MCDATA_BASE = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data"
CACHE = pathlib.Path.home() / ".cache" / "velcro-packet-reports"
REPO = pathlib.Path(__file__).resolve().parents[2]
OUTPUT = (REPO / "proxy/src/main/java/com/velocitypowered/proxy"
          / "protocol/tracking/TrackedPacketTables.java")

HEADER = """\
/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
"""


def fetch_json(url):
    with urllib.request.urlopen(url) as response:
        return json.load(response)


def packets_report(mc_version, manifest):
    report = CACHE / mc_version / "packets.json"
    if report.exists():
        return json.loads(report.read_text())

    entry = next((v for v in manifest["versions"] if v["id"] == mc_version), None)
    if entry is None:
        sys.exit(f"Minecraft version {mc_version} not found in Mojang's version manifest")

    server_jar = CACHE / mc_version / "server.jar"
    server_jar.parent.mkdir(parents=True, exist_ok=True)
    if not server_jar.exists():
        url = fetch_json(entry["url"])["downloads"]["server"]["url"]
        print(f"  downloading server jar for {mc_version}...")
        urllib.request.urlretrieve(url, server_jar)

    print(f"  running data generator for {mc_version}...")
    workdir = CACHE / mc_version / "datagen"
    workdir.mkdir(exist_ok=True)
    subprocess.run(
        ["java", "-DbundlerMainClass=net.minecraft.data.Main", "-jar",
         str(server_jar), "--reports"],
        cwd=workdir, check=True, capture_output=True,
    )
    generated = workdir / "generated" / "reports" / "packets.json"
    if not generated.exists():
        # Versions before the vanilla packets report; fall back to minecraft-data and
        # cache the result in the same shape.
        print(f"  no packets report in {mc_version}; falling back to minecraft-data...")
        report.write_text(json.dumps(mcdata_report(mc_version)))
        return json.loads(report.read_text())
    report.write_text(generated.read_text())
    return json.loads(report.read_text())


def mcdata_report(mc_version):
    data_paths = fetch_json(f"{MCDATA_BASE}/dataPaths.json")["pc"]
    entry = data_paths.get(mc_version)
    if entry is None:
        # minecraft-data keys some releases by their minor version only.
        entry = data_paths.get(mc_version.rsplit(".", 1)[0])
    if entry is None or "protocol" not in entry:
        sys.exit(f"{mc_version}: not found in minecraft-data either")

    protocol = fetch_json(f"{MCDATA_BASE}/{entry['protocol']}/protocol.json")

    def direction_ids(direction, mojang_names, mcdata_names):
        mappings = protocol["play"][direction]["types"]["packet"][1][0]["type"][1]["mappings"]
        by_name = {name: int(pid, 16) for pid, name in mappings.items()}
        ids = {}
        for mojang_name, mcdata_name in zip(mojang_names, mcdata_names):
            if mcdata_name not in by_name:
                if mojang_name in OPTIONAL:
                    continue
                sys.exit(f"{mc_version}: packet {mcdata_name} missing from minecraft-data")
            ids[mojang_name] = {"protocol_id": by_name[mcdata_name]}
        return ids

    return {"play": {
        "clientbound": direction_ids("toClient", TRACKED_CLIENTBOUND, MCDATA_NAMES),
        "serverbound": direction_ids("toServer", TRACKED_SERVERBOUND, MCDATA_SERVERBOUND),
    }}


def extract_ids(report, mc_version):
    ids = []
    for direction, names in (("clientbound", TRACKED_CLIENTBOUND),
                             ("serverbound", TRACKED_SERVERBOUND)):
        packets = report["play"][direction]
        for name in names:
            if name not in packets:
                if name in OPTIONAL:
                    ids.append(-1)
                    continue
                sys.exit(f"{mc_version}: packet {name} missing from packets.json; "
                         "it was likely renamed and the tracked lists need updating")
            ids.append(packets[name]["protocol_id"])
    return ids


def render(tables):
    lines = [HEADER]
    lines.append("""package com.velocitypowered.proxy.protocol.tracking;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.Map;

/**
 * The tracked packet ID tables for every supported protocol version, extracted from the
 * vanilla data generator's packets.json report per version.
 *
 * <p>Generated by {@code tools/tracked-packets/generate.py}. Do not edit by hand; to add a
 * version, append it to the script's version list and rerun it.</p>
 */
final class TrackedPacketTables {

  static final Map<ProtocolVersion, TrackedPackets> TABLES = Map.ofEntries(""")
    entries = []
    for enum_name, _, mc_version, ids in tables:
        args = ", ".join(f"0x{i:02X}" if i >= 0 else "-1" for i in ids)
        entries.append(
            f"      // {mc_version}\n"
            f"      Map.entry(ProtocolVersion.{enum_name},\n"
            f"          new TrackedPackets({args}))")
    lines.append(",\n".join(entries))
    lines.append("""  );

  private TrackedPacketTables() {
    throw new AssertionError();
  }
}
""")
    return "\n".join(lines)


def main():
    print("fetching version manifest...")
    manifest = fetch_json(MANIFEST_URL)
    tables = []
    for enum_name, protocol, mc_version in VERSIONS:
        print(f"protocol {protocol} ({mc_version}):")
        ids = extract_ids(packets_report(mc_version, manifest), mc_version)
        print(f"  {' '.join(f'0x{i:02X}' if i >= 0 else '-1' for i in ids)}")
        tables.append((enum_name, protocol, mc_version, ids))
    OUTPUT.write_text(render(tables))
    print(f"wrote {OUTPUT}")


if __name__ == "__main__":
    main()

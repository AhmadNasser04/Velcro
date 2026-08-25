# Maintenance notes

How the seamless switch machinery fits together and what to check when updating Minecraft
versions. Most breakage here doesn't error, switches just quietly stop being seamless, so skim
this before changing things.

## Where things are

- `AbsorbingConfigSessionHandler` - runs a backend's config phase on the client's behalf.
  Aborts and retries as a normal switch on anything that needs a real client
- `SeamlessSwitchFingerprint` + `Cache` - canonical hash of a config phase, ViaVersion
  tolerant. The cache is only a hint, a stale entry can't cause a wrong switch
- `PlayerEntityTrackerHandler` - outbound tap on the client connection: entities, effects,
  scoreboards, chunk/recipe/tag dedup, position sync rewrite
- `ClientStateTracker` / `TrackedPackets` - per-version packet id tables and the raw removal
  packets reconciliation sends
- `SeamlessWorldTracker` - content hashes of the world data the client holds, trimmed the
  same way the client trims its own chunk cache
- `ClientPlaySessionHandler` - `canDoRespawnOnlySwitch` / `doSeamlessServerSwitch`, HUD
  reconciliation, chat session withholding
- `TransitionSessionHandler` - waits for JoinGame after an absorbed config phase, times out
  back to a normal switch
- `companion/` - Paper plugin that pins the proxy-owned entity id (`velcro:entity_id` cookie).
  Two jars from shared `common/` logic: `modern/` uses
  `PaperPlayerConfigurationConnection#setInternalPluginDefinedEntityId`, `legacy/` covers Paper
  builds without it (back to 1.21.11, the oldest with the connection API) by stashing the id at
  the configure event and reflectively calling `getHandle().setId()` at `PlayerSpawnLocationEvent`

## Adding a version

1. do the normal Velocity protocol bump first (ProtocolVersion, StateRegistry)
2. append `(ENUM_NAME, protocol, newest release on that protocol)` to VERSIONS in
   `tools/tracked-packets/generate.py` and rerun it. Needs Java and network, reports cache in
   `~/.cache/velcro-packet-reports`. Never hand-edit `TrackedPacketTables.java`
3. make sure StateRegistry covers the new version for `GameEventPacket` and that
   `RespawnPacket` stays decodable (`encodeOnly=false`) for it. Every version with a table
   must decode Respawn, otherwise dimension tracking breaks and a packetless switch can land
   in the wrong world
4. run the tests. `ClientStateTrackerTest` pins protocol 773's ids as a generator canary

If the script exits complaining a packet is missing, it got renamed or removed. Renames: add
the new name to `ALIASES`, which is how `horse_screen_open` becoming `mount_screen_open` in
26.2 is handled. Removed: add it to `OPTIONAL` so it's emitted as -1, which is how
`add_experience_orb` is handled (merged into `add_entity` in 1.21.5).

## Wire formats the tracker assumes

- chunk data: int x, int z, then hashed body
- forget chunk: one long, z in the high bits so z reads first
- objectives/teams: string name then mode byte (0 create, 1 remove)
- add entity / experience orb / effect packets: varint entity id first
- open_screen: varint container id (varint on every version, unlike the close packet)
- container close and horse/mount screen open: container id is u8 before 1.21.2, varint from
  it on (`ClientStateTracker.readContainerId`)
- set_camera: varint entity id
- position sync (1.21.2+): varint teleport id, 3 doubles position, 3 doubles delta, yaw,
  pitch, int flags

If any of these change, `PlayerEntityTrackerHandler` logs "Failed to track an outbound
packet" once per player. Treat that as broken, the tracked state is drifting.

## Gotchas

- secure chat is off network wide when this is enabled. Backends need
  `enforce-secure-profile=false` or they reject the unsigned chat the proxy forwards
- the shadowJar strips most of fastutil (`proxy/build.gradle.kts`). A new fastutil type
  compiles fine and dies at runtime in the `-all` jar, check the excludes first
- the fingerprint gate assumes identical backends. Datapack or plugin drift means permanent
  fallback to normal switches, which is on purpose, don't loosen the match
- entity ids are proxy-owned starting at 1.6b so they can't collide with backend ids counting
  up from 0. One proxy per network
- everything runs on the player's event loop (backend connections bootstrap onto it), which
  is why none of the tracking state is synchronized. Keep it that way

# Velcro

Velocity fork that makes server switches seamless. No "Reconfiguring" screen, no "Loading
terrain" screen, and if the target server has the same registries and dimension the client
gets no world reset at all.

It works by running the backend's config phase on the proxy and fingerprinting it, skipping
JoinGame when the client already has everything, and cleaning up whatever a respawn would have
reset (old entities, effects, boss bars, scoreboards, duplicate chunk data). Anything that
doesn't match falls back to a normal switch. Internals are documented in
[tools/MAINTENANCE.md](tools/MAINTENANCE.md).

## Setup

- `./gradlew build`, run the `-all` jar from `proxy/build/libs` like normal Velocity
- `./gradlew :companion:build`, drop the jar in every backend's `plugins/` folder
- set `enforce-secure-profile=false` on every backend. Chat is forwarded unsigned since
  signed chat can't survive a switch without a JoinGame
- in velocity.toml:

```toml
[advanced]
seamless-server-switches = true
```

Config phase skipping needs 1.20.2+ clients. Fully packetless switches need 1.20.5-26.2 and
identical datapacks/plugins across the backends. Older clients behave like stock Velocity.

## Adding a version

Append it to `tools/tracked-packets/generate.py` and rerun, never hand-edit the generated
table. Checklist in [tools/MAINTENANCE.md](tools/MAINTENANCE.md).

## License

GPLv3, same as Velocity. The API is untouched so existing plugins work unchanged.

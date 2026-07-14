# Vendored acceptance fixtures

Source: [EtaCassiopeia/rift](https://github.com/EtaCassiopeia/rift) `examples/*.json`
Version: **v0.13.5** (commit `92719e7`) — the engine version pinned by rift-java 0.1.1 (D9).

Copied verbatim. Issue #2's acceptance criterion is "decode -> encode round-trips
`rift/examples/*.json`", and the fixtures live in a different repository, so they are vendored
here to keep CI hermetic (no network, no submodule).

Refresh them when the engine pin moves, together with `Dependencies.riftJava`. The *conformance*
corpus is a separate, larger artifact fetched at build time by the `conformance` module
(issues #6/#13) — these six files are only the model round-trip gate.

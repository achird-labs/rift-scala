# Vendored acceptance fixtures

Source: [achird-labs/rift](https://github.com/achird-labs/rift) `examples/*.json`
Version: **v0.14.0** (commit `6ff6357`) — the engine version pinned by rift-java 0.1.2 (D9).
The files are byte-identical to the v0.13.5 vendoring (`git diff v0.13.5..v0.14.0 -- examples/`
is empty), so the 0.14.0 re-verification changed nothing here.

Copied verbatim. Issue #2's acceptance criterion is "decode -> encode round-trips
`rift/examples/*.json`", and the fixtures live in a different repository, so they are vendored
here to keep CI hermetic (no network, no submodule).

Refresh them when the engine pin moves, together with `Dependencies.riftJava`. The *conformance*
corpus is a separate, larger artifact fetched at build time by the `conformance` module
(issues #6/#13) — these six files are only the model round-trip gate.

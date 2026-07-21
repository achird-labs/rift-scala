# Vendored acceptance fixtures

Source: [achird-labs/rift](https://github.com/achird-labs/rift) `examples/*.json`
Version: **v0.15.0** — the engine version pinned by rift-java 0.1.3 (D9). The release tag identifies
the content; a commit sha is deliberately not repeated here, because `scripts/bump.sh` re-stamps the
version on a bump and cannot derive the sha, which would leave the two disagreeing.
The files have been byte-identical since the v0.13.5 vendoring (`git diff v0.13.5..v0.14.0 --
examples/` is empty), so both the 0.14.0 and 0.15.0 re-verifications changed nothing here.

Copied verbatim. Issue #2's acceptance criterion is "decode -> encode round-trips
`rift/examples/*.json`", and the fixtures live in a different repository, so they are vendored
here to keep CI hermetic (no network, no submodule).

Refresh them when the engine pin moves, together with `Dependencies.riftJava`. The *conformance*
corpus is a separate, larger artifact fetched at build time by the `conformance` module
(issues #6/#13) — these six files are only the model round-trip gate.

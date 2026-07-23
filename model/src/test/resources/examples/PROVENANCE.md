# Vendored acceptance fixtures

Source: [achird-labs/rift](https://github.com/achird-labs/rift) `examples/*.json`
Version: **v0.16.0** — the engine version pinned by rift-java 0.2.1 (D9). The release tag identifies
the content; a commit sha is deliberately not repeated here, because `scripts/bump.sh` re-stamps the
version on a bump and cannot derive the sha, which would leave the two disagreeing.
The files were byte-identical from the v0.13.5 vendoring through v0.15.0, so the 0.14.0 and 0.15.0
re-verifications changed nothing here. **v0.16.0 is the first bump that moved a fixture:**
`task-management-api.json` was re-vendored after [rift#805](https://github.com/achird-labs/rift/pull/805)
reordered its stubs so the narrower `TaskAPI-GetTasks-FilterByStatus` /
`TaskAPI-GetTaskById-NotFound` stubs precede their catch-alls, and anchored the not-found path regex
(`^/tasks/task-999$`) — first-match-wins semantics made the original ordering unreachable.

Copied verbatim. Issue #2's acceptance criterion is "decode -> encode round-trips
`rift/examples/*.json`", and the fixtures live in a different repository, so they are vendored
here to keep CI hermetic (no network, no submodule).

Refresh them when the engine pin moves, together with `Dependencies.riftJava`. The *conformance*
corpus is a separate, larger artifact fetched at build time by the `conformance` module
(issues #6/#13) — these six files are only the model round-trip gate.

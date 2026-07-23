# Corpus fixtures — provenance

These 15 imposter fixtures are vendored verbatim from the Rift engine's published SDK conformance
corpus: **`sdk-conformance-v0.16.0.tar.gz`** (GitHub release asset for engine `v0.16.0`), under
`corpus/imposters/`. The corpus is the engine-canonical single source of truth for DSL↔engine
parity (RFC-003 §9.2); `CorpusRoundTripSpec` round-trips each through the model.

Version-locked to the engine pinned by this build (`RiftVersions.engine`, via `rift-java`). When the
engine pin bumps, refresh these from the matching `sdk-conformance-v<version>` release —
`scripts/bump.sh` re-stamps the version named here, but it cannot know whether the *fixtures*
changed, so diff them against the new release rather than trusting the stamp.

The fixtures have been byte-identical from v0.14.0 through v0.16.0 (only the corpus
`manifest.json`'s `engineVersion` moved), so those bumps were re-stamps with no content change.

The ZIO-surface conformance module (#6) fetches this same corpus at build time and replays it over
both transports; this vendored copy is the model-layer round-trip gate (#54).

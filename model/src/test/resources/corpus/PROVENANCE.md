# Corpus fixtures — provenance

These 15 imposter fixtures are vendored verbatim from the Rift engine's published SDK conformance
corpus: **`sdk-conformance-v0.14.0.tar.gz`** (GitHub release asset for engine `v0.14.0`), under
`corpus/imposters/`. The corpus is the engine-canonical single source of truth for DSL↔engine
parity (RFC-003 §9.2); `CorpusRoundTripSpec` round-trips each through the model.

Version-locked to the engine pinned by this build (`RiftVersions.engine`, via `rift-java`). When the
engine pin bumps, refresh these from the matching `sdk-conformance-v<version>` release.

The ZIO-surface conformance module (#6) fetches this same corpus at build time and replays it over
both transports; this vendored copy is the model-layer round-trip gate (#54).

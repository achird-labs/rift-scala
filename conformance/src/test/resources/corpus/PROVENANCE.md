# Provenance and refresh procedure

This directory is a **vendored, version-locked snapshot** of the engine's `sdk-conformance`
corpus (see `README.md` in this same directory for the corpus's own normative contract). It is
copied in wholesale, not generated — there is no build-time download step, so `sbt compile` /
`sbt test` never touch the network.

## Version lock

The corpus version **is** the engine version — there is no independent corpus versioning
(`README.md`'s `manifest.json` section). This SDK pins the engine transitively through
`Dependencies.riftJava` in `project/Dependencies.scala`:

```scala
/** Pins the engine (0.14.0) and the conformance corpus transitively. */
val riftJava = "0.1.2"
```

`rift-java` `0.1.2` pins engine `0.14.0`, which is exactly the `engineVersion` recorded in this
directory's `manifest.json`. **Never hand-edit `manifest.json`'s `engineVersion`** — it must always
match whatever engine version `Dependencies.riftJava` resolves to; if you bump `riftJava`, refresh
the corpus (below) in the same change so the two never drift apart.

## Refresh procedure (maintainer-only — not run during the build)

1. Determine the target engine version. Read it off the `rift-java` release notes for the
   `Dependencies.riftJava` version you are pinning (or, at runtime, `RiftVersions.engine` in
   `bridge/src/main/scala/rift/bridge/RiftVersions.scala` reports the resolved engine version
   for whatever `rift-java` is currently on the classpath).

2. Download the matching release asset from the **engine's** GitHub releases (not this repo's):

   ```sh
   VERSION=0.14.0   # engineVersion, no leading 'v' in the asset name
   curl -LO "https://github.com/<engine-org>/<engine-repo>/releases/download/v${VERSION}/sdk-conformance-v${VERSION}.tar.gz"
   curl -LO "https://github.com/<engine-org>/<engine-repo>/releases/download/v${VERSION}/sdk-conformance-v${VERSION}.tar.gz.sha256"
   ```

3. Verify the checksum before touching anything on disk:

   ```sh
   shasum -a 256 -c "sdk-conformance-v${VERSION}.tar.gz.sha256"
   ```

   Do not proceed if this fails — a corpus that doesn't match its published hash is not a
   version-locked artifact, it's an unverified download.

4. Unpack and replace this directory's contents wholesale (do **not** hand-merge — the corpus is
   append-only upstream, and a stale local fixture the release removed should disappear too):

   ```sh
   rm -rf /tmp/sdk-conformance && mkdir /tmp/sdk-conformance
   tar -xzf "sdk-conformance-v${VERSION}.tar.gz" -C /tmp/sdk-conformance
   rsync -a --delete "/tmp/sdk-conformance/sdk-conformance-${VERSION}/corpus/" \
     conformance/src/test/resources/corpus/
   cp "/tmp/sdk-conformance/sdk-conformance-${VERSION}/README.md" \
     conformance/src/test/resources/corpus/README.md
   cp "/tmp/sdk-conformance/sdk-conformance-${VERSION}/manifest.json" \
     conformance/src/test/resources/corpus/manifest.json
   ```

5. Re-run the gate (`sbt "conformance/test"`) and update `CorpusG2Spec`'s
   `dslExpressible`/`dslExpressibleModuloVerify`/`notDslExpressible` split for any fixture that is
   new, renumbered (never happens — fixtures are append-only) or changed shape. The
   `"every corpus fixture is accounted for"` test in `CorpusG2Spec` fails loudly if a new fixture
   id isn't triaged into one of the three buckets, so there is no way to silently miss one.

6. Bump `Dependencies.riftJava` in the same commit if this refresh was prompted by an engine bump,
   so the pin and the vendored corpus move together (never independently — see "Version lock"
   above).

## Why not an sbt `fetchCorpus` task

A robust version of this (download + sha256 verify + untar + rsync, with sensible error messages
for a bad checksum or a 404) is straightforward as a shell script but fiddly to get right as sbt
`Def.task` plumbing (streaming a `.tar.gz` through `IO`, wiring a real HTTP client without adding a
new dependency, propagating a checksum failure as a build error rather than a stack trace) for a
task that a maintainer runs by hand, rarely, and never in CI. This document is the deliberate
alternative: a precise, copy-pasteable procedure over automation that would mostly exist to be
copy-pasted from anyway.

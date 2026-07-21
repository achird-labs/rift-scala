#!/usr/bin/env bash
#
# The rift-scala-specific edit half of the dependency-bump loop, invoked by the reusable
# achird-labs/rift-java/.github/workflows/dep-bump.yml.
#
#   scripts/bump.sh --current        print the currently pinned rift-java version (bare, no leading v)
#   scripts/bump.sh <new-version>    rewrite the pin to <new-version> and self-verify
#
# `val riftJava` in project/Dependencies.scala is the single source of truth (it transitively pins the
# engine and the sdk-conformance corpus, DESIGN §7 / D9), and the repo's own release version comes
# from the git tag via dynver.
#
# It is NOT a one-line bump, though it was written as one. RiftVersionsSpec asserts both the rift-java
# pin and the transitive engine version as literals — deliberately, so a silent change of the engine
# under us is caught — and those literals do not move themselves. Leaving them behind is how a bump
# resolves cleanly and then fails in tests with a message that looks nothing like "the pin moved",
# which is exactly what the org-migration branch did. So they are rewritten here too, and the engine
# version is read from the rift-java POM being bumped to rather than guessed.

set -euo pipefail

PIN_FILE="project/Dependencies.scala"
VERSIONS_SPEC="bridge/src/test/scala/rift/bridge/BridgeUnitSpec.scala"
RIFT_JAVA_POM="https://repo1.maven.org/maven2/io/github/achird-labs/rift-java-parent"

current() {
  # Portable sed (GNU + BSD) so the script runs locally too.
  sed -n 's|.*val riftJava = "\([^"]*\)".*|\1|p' "${PIN_FILE}" | head -n1
}

if [ "${1:-}" = "--current" ]; then
  current
  exit 0
fi

NEW="${1:?usage: bump.sh --current | bump.sh <new-version>}"
CURRENT="$(current)"
if [ -z "${CURRENT}" ]; then
  echo "Could not read 'val riftJava' from ${PIN_FILE}." >&2
  exit 1
fi

# The engine version the new rift-java pins, straight from its POM. Fail loudly rather than guessing:
# a wrong literal here is a red build whose message points at the engine, not at this script. Note
# Central's rsync lags a release by ~10-30 minutes, so a bump attempted in that window fails here —
# re-run once the POM is served rather than hand-editing around it.
ENGINE="$(curl -fsSL "${RIFT_JAVA_POM}/${NEW}/rift-java-parent-${NEW}.pom" 2>/dev/null \
  | sed -n 's|.*<rift\.engine\.version>\([^<]*\)</rift\.engine\.version>.*|\1|p' | head -n1)"
if [ -z "${ENGINE}" ]; then
  echo "Could not read <rift.engine.version> from rift-java-parent ${NEW} on Maven Central." >&2
  echo "If ${NEW} was released in the last half hour this is Central's rsync lag — retry shortly." >&2
  exit 1
fi

sed -i.bak "s|val riftJava = \"${CURRENT}\"|val riftJava = \"${NEW}\"|" "${PIN_FILE}"
# The doc comment on the pin names the engine it drags in; it is the first thing a reader trusts.
sed -i.bak "s|Pins the engine ([0-9][^)]*)|Pins the engine (${ENGINE})|" "${PIN_FILE}"
# RiftVersionsSpec's two drift-catchers.
sed -i.bak "s|assertEquals(RiftVersions.riftJava, \"[^\"]*\")|assertEquals(RiftVersions.riftJava, \"${NEW}\")|" "${VERSIONS_SPEC}"
sed -i.bak "s|assertEquals(RiftVersions.engine, \"[^\"]*\")|assertEquals(RiftVersions.engine, \"${ENGINE}\")|" "${VERSIONS_SPEC}"
rm -f "${PIN_FILE}.bak" "${VERSIONS_SPEC}.bak"

# Fail loudly if any target still carries the old version — a half-bumped tree ships a red PR whose
# failure names the engine rather than the bump that caused it.
if grep -q "val riftJava = \"${CURRENT}\"" "${PIN_FILE}" \
   || ! grep -q "val riftJava = \"${NEW}\"" "${PIN_FILE}" \
   || ! grep -q "assertEquals(RiftVersions.riftJava, \"${NEW}\")" "${VERSIONS_SPEC}" \
   || ! grep -q "assertEquals(RiftVersions.engine, \"${ENGINE}\")" "${VERSIONS_SPEC}"; then
  echo "Failed to bump ${CURRENT} -> ${NEW}; a version-tracking literal was not updated." >&2
  exit 1
fi

echo "Bumped rift-java ${CURRENT} -> ${NEW} (engine ${ENGINE})"

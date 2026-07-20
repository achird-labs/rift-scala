#!/usr/bin/env bash
#
# The rift-scala-specific edit half of the dependency-bump loop, invoked by the reusable
# achird-labs/rift-java/.github/workflows/dep-bump.yml.
#
#   scripts/bump.sh --current        print the currently pinned rift-java version (bare, no leading v)
#   scripts/bump.sh <new-version>    rewrite the pin to <new-version> and self-verify
#
# `val riftJava` in project/Dependencies.scala is the single source of truth (it transitively pins the
# engine and the sdk-conformance corpus, DESIGN §7 / D9); no other literal hard-codes the version, and
# the repo's own release version comes from the git tag via dynver — so this is a one-line bump.

set -euo pipefail

PIN_FILE="project/Dependencies.scala"

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

sed -i.bak "s|val riftJava = \"${CURRENT}\"|val riftJava = \"${NEW}\"|" "${PIN_FILE}"
rm -f "${PIN_FILE}.bak"

if grep -q "val riftJava = \"${CURRENT}\"" "${PIN_FILE}" \
   || ! grep -q "val riftJava = \"${NEW}\"" "${PIN_FILE}"; then
  echo "Failed to bump ${CURRENT} -> ${NEW}; the 'val riftJava' pin was not updated." >&2
  exit 1
fi

echo "Bumped rift-java ${CURRENT} -> ${NEW}"

#!/usr/bin/env bash
#
# Regression gate for scripts/bump.sh (#117).
#
#   scripts/bump-selftest.sh
#
# bump.sh is automation whose whole job is to fail loudly when a version literal drifts, and nothing
# tested it. Both bugs it has had were the same shape: `sed` exits 0 when it matches nothing, so a
# rewrite that reaches nothing is indistinguishable from a no-op unless something asserts the
# result. #115 found one by eye (the README a version behind) and, while fixing it, a second nobody
# had noticed at all — a loose pattern ate the dot before `.tar.gz`, so every run rewrote
# `sdk-conformance-v<v>.tar.gz` into `…v<v>tar.gz`, the script minting the typo it then re-versioned
# around on the next run. PR #116 added verify arms for every re-stamp target; this proves those
# arms keep working.
#
# The mutation cases are the point. A positive-only test passes just as happily against a bump.sh
# whose verify block was deleted.
#
# Hermetic by construction:
#   - each case runs in a sandbox holding ONLY the seven files bump.sh touches, so the repo is never
#     written to (asserted at the end);
#   - a stub `curl` on PATH serves a synthetic rift-java-parent POM, so the test is offline,
#     deterministic, and can exercise a fictional bump without waiting on Maven Central;
#   - no version literal is hardcoded: the fixtures are copied from the working tree and every
#     assertion is written against what the bump was asked to produce.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUMP="${REPO_ROOT}/scripts/bump.sh"

# A version that is not the pinned one, and an engine to match. Fictional on purpose — the stub POM
# is the only thing that has to agree with it.
NEW_VERSION="9.9.9"
NEW_ENGINE="8.8.8"

# The seven files bump.sh rewrites. Kept in one list so a target added to bump.sh without a fixture
# here fails this test rather than going unchecked.
FIXTURES=(
  "project/Dependencies.scala"
  "bridge/src/test/scala/rift/bridge/BridgeUnitSpec.scala"
  "model/src/test/resources/corpus/PROVENANCE.md"
  "model/src/test/resources/examples/PROVENANCE.md"
  "model/src/test/scala/rift/model/CorpusRoundTripSpec.scala"
  "README.md"
  "docs/index.md"
)

PASS=0
FAIL=0
SANDBOXES=()

pass() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
fail() { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$1" >&2; }

check() { # <description> <condition-exit-status>
  if [ "$2" -eq 0 ]; then pass "$1"; else fail "$1"; fi
}

cleanup() {
  local dir
  for dir in "${SANDBOXES[@]:-}"; do
    [ -n "${dir}" ] && [ -d "${dir}" ] && rm -rf "${dir}"
  done
}
trap cleanup EXIT

# A sandbox containing only the fixtures, plus a stub `curl` that serves the synthetic POM.
# `$1` (optional) = "fail" to make the stub curl exit non-zero, exercising the rsync-lag path.
new_sandbox() {
  local mode="${1:-ok}"
  local dir
  dir="$(mktemp -d)"
  SANDBOXES+=("${dir}")

  local f
  for f in "${FIXTURES[@]}"; do
    mkdir -p "${dir}/$(dirname "${f}")"
    cp "${REPO_ROOT}/${f}" "${dir}/${f}"
  done

  mkdir -p "${dir}/.stub"
  if [ "${mode}" = "fail" ]; then
    printf '#!/bin/sh\nexit 22\n' > "${dir}/.stub/curl"
  else
    # Only the one property bump.sh reads; anything else would be theatre.
    printf '#!/bin/sh\ncat <<XML\n<project><properties><rift.engine.version>%s</rift.engine.version></properties></project>\nXML\n' \
      "${NEW_ENGINE}" > "${dir}/.stub/curl"
  fi
  chmod +x "${dir}/.stub/curl"

  printf '%s' "${dir}"
}

run_bump() { # <sandbox> <args...> — stdout+stderr to $OUT, exit status returned
  local dir="$1"
  shift
  OUT="${dir}/.bump-output"
  ( cd "${dir}" && PATH="${dir}/.stub:${PATH}" "${BUMP}" "$@" ) > "${OUT}" 2>&1
}

echo "bump.sh selftest"
echo

# ── 1. --current reads the pin ────────────────────────────────────────────────────────────────────
SB="$(new_sandbox)"
if run_bump "${SB}" --current; then
  CURRENT_VERSION="$(cat "${OUT}")"
  check "--current prints the pinned version, bare" \
    "$([ -n "${CURRENT_VERSION}" ] && [[ "${CURRENT_VERSION}" != v* ]] && echo 0 || echo 1)"
else
  fail "--current exits 0"
  CURRENT_VERSION=""
fi

# ── 2. a genuine bump rewrites every target ───────────────────────────────────────────────────────
SB="$(new_sandbox)"
if run_bump "${SB}" "${NEW_VERSION}"; then
  pass "a genuine bump exits 0"

  check "the pin moves" \
    "$(grep -q "val riftJava = \"${NEW_VERSION}\"" "${SB}/project/Dependencies.scala" && echo 0 || echo 1)"
  check "the pin's engine comment moves" \
    "$(grep -q "Pins the engine (${NEW_ENGINE})" "${SB}/project/Dependencies.scala" && echo 0 || echo 1)"
  check "RiftVersionsSpec's rift-java literal moves" \
    "$(grep -q "assertEquals(RiftVersions.riftJava, \"${NEW_VERSION}\")" \
        "${SB}/bridge/src/test/scala/rift/bridge/BridgeUnitSpec.scala" && echo 0 || echo 1)"
  check "RiftVersionsSpec's engine literal moves" \
    "$(grep -q "assertEquals(RiftVersions.engine, \"${NEW_ENGINE}\")" \
        "${SB}/bridge/src/test/scala/rift/bridge/BridgeUnitSpec.scala" && echo 0 || echo 1)"
  check "the corpus provenance names the new engine" \
    "$(grep -q "engine \`v${NEW_ENGINE}\`" "${SB}/model/src/test/resources/corpus/PROVENANCE.md" \
        && echo 0 || echo 1)"
  check "the examples provenance moves both literals" \
    "$(grep -q "Version: \*\*v${NEW_ENGINE}\*\*" "${SB}/model/src/test/resources/examples/PROVENANCE.md" \
        && grep -q "pinned by rift-java ${NEW_VERSION}" \
             "${SB}/model/src/test/resources/examples/PROVENANCE.md" && echo 0 || echo 1)"
  check "CorpusRoundTripSpec names the new release" \
    "$(grep -q "sdk-conformance-v${NEW_ENGINE}" \
        "${SB}/model/src/test/scala/rift/model/CorpusRoundTripSpec.scala" && echo 0 || echo 1)"
  check "the README coordinates move" \
    "$(grep -q "\"rift-java-embedded\" % \"${NEW_VERSION}\"" "${SB}/README.md" \
        && grep -q "\"rift-java-natives\" % \"${NEW_VERSION}\"" "${SB}/README.md" && echo 0 || echo 1)"
  check "docs/index.md coordinates move" \
    "$(grep -q "\"rift-java-embedded\" % \"${NEW_VERSION}\"" "${SB}/docs/index.md" \
        && grep -q "\"rift-java-natives\" % \"${NEW_VERSION}\"" "${SB}/docs/index.md" && echo 0 || echo 1)"

  # #115's regression, the one that regenerated itself on every run.
  check "the corpus filename keeps its dot (#115: no 'v<engine>tar.gz')" \
    "$(grep -q "sdk-conformance-v${NEW_ENGINE}\.tar\.gz" \
        "${SB}/model/src/test/resources/corpus/PROVENANCE.md" && echo 0 || echo 1)"
  check "no dotless corpus filename anywhere (#115)" \
    "$(grep -rq "sdk-conformance-v${NEW_ENGINE}tar\.gz" "${SB}" && echo 1 || echo 0)"

  check "no .bak files survive" \
    "$(find "${SB}" -name '*.bak' | grep -q . && echo 1 || echo 0)"
else
  fail "a genuine bump exits 0"
  cat "${OUT}" >&2
fi

# ── 3. re-stamping the same version is idempotent ─────────────────────────────────────────────────
# A re-stamp at the current version is legitimate — it repairs provenance that drifted without a
# bump — so it must succeed AND change nothing the second time.
SB="$(new_sandbox)"
if run_bump "${SB}" "${NEW_VERSION}"; then
  BEFORE="$(mktemp -d)"
  SANDBOXES+=("${BEFORE}")
  for f in "${FIXTURES[@]}"; do
    mkdir -p "${BEFORE}/$(dirname "${f}")"
    cp "${SB}/${f}" "${BEFORE}/${f}"
  done
  if run_bump "${SB}" "${NEW_VERSION}"; then
    pass "an idempotent re-stamp exits 0"
    ident=0
    for f in "${FIXTURES[@]}"; do
      cmp -s "${BEFORE}/${f}" "${SB}/${f}" || ident=1
    done
    check "an idempotent re-stamp is byte-for-byte identical" "${ident}"
    check "no .bak files survive the re-stamp" \
      "$(find "${SB}" -name '*.bak' | grep -q . && echo 1 || echo 0)"
  else
    fail "an idempotent re-stamp exits 0"
    cat "${OUT}" >&2
  fi
else
  fail "idempotency setup bump exits 0"
fi

# ── 4. mutation cases — the valuable half ─────────────────────────────────────────────────────────
# Mangle one re-stamp target so its pattern can no longer match, and require the bump to fail. These
# are what caught the missing verify arms in #116's review; without them, deleting the whole verify
# block still passes this suite.
#
# The mutations are version-agnostic (they corrupt the pattern's anchor, not a literal) so they keep
# working across bumps.
mutation_case() { # <label> <file> <sed-expression> <expect-file-named-in-error: yes|no>
  local label="$1" file="$2" expr="$3" names_file="$4"
  local dir
  dir="$(new_sandbox)"
  sed -i.bak "${expr}" "${dir}/${file}"
  rm -f "${dir}/${file}.bak"

  if run_bump "${dir}" "${NEW_VERSION}"; then
    fail "mutation: ${label} — bump should have failed but exited 0"
  else
    if [ "${names_file}" = "yes" ] && ! grep -q "${file}" "${OUT}"; then
      fail "mutation: ${label} — failed, but the error does not name ${file}"
    else
      pass "mutation: ${label}"
    fi
  fi
}

mutation_case "an unreadable pin" \
  "project/Dependencies.scala" 's|val riftJava = "|val riftJavaMANGLED = "|' yes
mutation_case "RiftVersionsSpec's drift-catcher cannot match" \
  "bridge/src/test/scala/rift/bridge/BridgeUnitSpec.scala" \
  's|RiftVersions\.riftJava,|RiftVersions.riftJavaMANGLED,|' no
mutation_case "the corpus provenance filename cannot match" \
  "model/src/test/resources/corpus/PROVENANCE.md" 's|sdk-conformance-v|sdk-conformance-MANGLED-v|g' yes
mutation_case "CorpusRoundTripSpec's release name cannot match" \
  "model/src/test/scala/rift/model/CorpusRoundTripSpec.scala" \
  's|sdk-conformance-v|sdk-conformance-MANGLED-v|g' yes
mutation_case "the examples provenance version line cannot match" \
  "model/src/test/resources/examples/PROVENANCE.md" 's|Version: \*\*v|Version: **MANGLED-v|' yes
mutation_case "the README coordinate cannot match" \
  "README.md" 's|"rift-java-embedded" % "|"rift-java-embeddedMANGLED" % "|' yes
mutation_case "the docs/index.md coordinate cannot match" \
  "docs/index.md" 's|"rift-java-embedded" % "|"rift-java-embeddedMANGLED" % "|' yes

# ── 5. a stale coordinate the rewrite cannot reach is still detected ──────────────────────────────
# The rewrite anchors on `"rift-java-x" % "…"`; a differently-spaced second occurrence is left
# behind while the first satisfies the presence check. That is what the process-substitution
# detector in bump.sh exists for.
SB="$(new_sandbox)"
printf '\n    "io.github.achird-labs" %%%% "rift-java-embedded"  %%  "0.0.1",\n' >> "${SB}/README.md"
if run_bump "${SB}" "${NEW_VERSION}"; then
  fail "a stale, differently-spaced coordinate is detected"
else
  check "a stale, differently-spaced coordinate is detected" \
    "$(grep -q "README.md" "${OUT}" && echo 0 || echo 1)"
fi

# ── 6. an unresolvable engine version fails loudly ────────────────────────────────────────────────
SB="$(new_sandbox fail)"
if run_bump "${SB}" "${NEW_VERSION}"; then
  fail "an unreachable POM fails the bump"
else
  check "an unreachable POM fails the bump, naming the rsync lag" \
    "$(grep -q "rsync lag" "${OUT}" && echo 0 || echo 1)"
  check "an unreachable POM leaves the pin untouched" \
    "$(grep -q "val riftJava = \"${CURRENT_VERSION}\"" "${SB}/project/Dependencies.scala" \
        && echo 0 || echo 1)"
fi

# ── 7. the repo's own fixtures were never written to ──────────────────────────────────────────────
# Scoped to the seven files rather than the whole tree: a dirty checkout is the normal state while
# working on this script, and asserting cleanliness there would make the suite fail for the author
# rather than for a bug.
if git -C "${REPO_ROOT}" rev-parse --git-dir > /dev/null 2>&1; then
  check "bump.sh never wrote into the real repo" \
    "$([ -z "$(git -C "${REPO_ROOT}" status --porcelain -- "${FIXTURES[@]}")" ] && echo 0 || echo 1)"
fi

echo
echo "passed ${PASS}, failed ${FAIL}"
[ "${FAIL}" -eq 0 ]

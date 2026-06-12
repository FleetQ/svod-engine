#!/usr/bin/env bash
# Self-update skeleton for the Svod engine.
#
# CONTRACT: the binary swap MUST be gated on the App API compatibility preflight. Same major
# contract version => apply; major bump or downgrade => refuse (see ADR-0007 / ApiCompatibility).
#
# This is a skeleton: artifact download + verification + the real jpackage app-image swap are
# the next packaging iteration. It exists to pin the *order of operations*.
set -euo pipefail

LABEL="dev.svod.engine"
INSTALL_JAR="/usr/local/svod/svod-engine.jar"
RUNNING_VERSION="$(curl -fsS "http://127.0.0.1:7517/api/v1/settings" | sed -n 's/.*"apiVersion":"\([^"]*\)".*/\1/p')"
CANDIDATE_VERSION="${1:?usage: self-update.sh <candidate-contract-version> <candidate-jar>}"
CANDIDATE_JAR="${2:?usage: self-update.sh <candidate-contract-version> <candidate-jar>}"

echo "running contract: ${RUNNING_VERSION:-unknown}   candidate: ${CANDIDATE_VERSION}"

# 1. API-compat preflight (major must match; no downgrade).
run_major="${RUNNING_VERSION%%.*}"
cand_major="${CANDIDATE_VERSION%%.*}"
if [[ "${run_major}" != "${cand_major}" ]]; then
  echo "REFUSED: major API version change ${RUNNING_VERSION} -> ${CANDIDATE_VERSION} breaks the contract." >&2
  exit 1
fi

# 2. (TODO) verify the candidate artifact checksum/signature before trusting it.

# 3. Swap + restart under launchd (KeepAlive brings it back; graceful shutdown drains first).
echo "applying update -> ${INSTALL_JAR}"
cp "${CANDIDATE_JAR}" "${INSTALL_JAR}.new"
mv "${INSTALL_JAR}.new" "${INSTALL_JAR}"   # atomic
launchctl kickstart -k "gui/$(id -u)/${LABEL}"

echo "update applied; poll /ready then reconnect."

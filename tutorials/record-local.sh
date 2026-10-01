#!/usr/bin/env bash
# Record one tutorial end to end against the LOCAL recording stack
# (tutorials/recording-stack.sh): proof, capture, narration, render, QA gates.
#
#   tutorials/record-local.sh <id> [voice] [words-per-minute]
#
# Media stays in the repo: final MP4 + .srt -> tutorials/output/, narration audio
# -> tutorials/audio-generated/, raw takes -> tutorials/raw/, intermediates ->
# tutorials/work/<id>/. Once the QA gates PASS, the raw takes, the silent webm
# and tutorials/work/<id>/ are pruned (tutorials/prune-tutorial-media.sh). A
# failed take keeps its raw capture and gets a REJECTED.md for review.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <tutorial-id> [voice] [words-per-minute]" >&2
  exit 2
fi
id=$(printf '%02d' "$((10#$1))")
repo=$(cd "$(dirname "$0")/.." && pwd)
tut="$repo/tutorials"

export PROD_BASE_URL=${TUTORIAL_WEB_URL:-http://localhost:${TUTORIAL_WEB_PORT:-3004}}
backend_url=${TUTORIAL_BACKEND_URL:-http://localhost:${TUTORIAL_BACKEND_PORT:-8084}}
case "$PROD_BASE_URL $backend_url" in
  http://localhost:*" "http://localhost:*|http://127.0.0.1:*" "http://127.0.0.1:*) ;;
  *) echo "Refusing: record-local.sh records against a localhost stack only ($PROD_BASE_URL, $backend_url)." >&2; exit 2 ;;
esac
# The accounting track records on Palm Ridge Properties (tutorials/seed/seed_palm_ridge.py);
# everything else on Oasis Crest.
case "$id" in
  14|17|18|19|20|35|36|37|38|39|40|41|42|43|44|45|46) default_manifest="$tut/work/seed/palm-ridge.out.json" ;;
  *) default_manifest="$tut/work/seed/oasis-crest.out.json" ;;
esac
export TUTORIAL_SEED_MANIFEST=${TUTORIAL_SEED_MANIFEST:-$default_manifest}
export TUTORIAL_AUTH_STATE=${TUTORIAL_AUTH_STATE:-"$tut/.auth/superadmin-local.json"}
export TUTORIAL_OUTPUT_DIR="$tut/output"
export TUTORIAL_AUDIO_OUTPUT_DIR="$tut/audio-generated"
export TUTORIAL_RAW_DIR="$tut/raw"
export TUTORIAL_WORK_DIR="$tut/work/$id"
export TUTORIAL_TTS_PROVIDER=azure
export TUTORIAL_VOICE=${2:-${TUTORIAL_VOICE:-en-US-Ava:DragonHDLatestNeural}}
export TUTORIAL_SPEECH_RATE=${3:-${TUTORIAL_SPEECH_RATE:-140}}
export TUTORIAL_ENV_FILE=${TUTORIAL_ENV_FILE:-"$tut/.env.local"}

narration=$(find "$tut/narration" -maxdepth 1 -type f -name "${id}-*.txt" -print -quit)
[[ -n "$narration" ]] || { echo "Narration not found for tutorial $id" >&2; exit 2; }
slug=$(basename "$narration" .txt)
[[ -f "$TUTORIAL_SEED_MANIFEST" ]] || { echo "Seed manifest missing: run tutorials/recording-stack.sh seed" >&2; exit 2; }
curl -fsS -o /dev/null "$PROD_BASE_URL/en/auth/login" \
  || { echo "Web stack is not up at $PROD_BASE_URL: run tutorials/recording-stack.sh start" >&2; exit 2; }

mkdir -p "$TUTORIAL_WORK_DIR" "$tut/qa"
log="$TUTORIAL_WORK_DIR/record.log"
: > "$log"

# A tutorial whose flow cannot be undone in the app (posting, terminating)
# has a database snapshot of its starting state, `recording-stack.sh snapshot
# pre<id>`. When it exists, the proof and the capture each start from it.
reset_to_snapshot() {
  if [[ "$(psql -h "${TUTORIAL_DB_HOST:-127.0.0.1}" -p "${TUTORIAL_DB_PORT:-5432}" -U postgres -Atc \
      "select 1 from pg_database where datname='${TUTORIAL_DB:-rentaxis_tutorials}_snap_pre$id'" postgres)" == 1 ]]; then
    echo "== restoring snapshot pre$id"
    "$tut/recording-stack.sh" restore "pre$id" 2>&1 | tee -a "$log"
  fi
}
reset_to_snapshot

# Fresh local super-admin session (the helper refuses non-localhost and
# non-gitignored output paths).
node "$tut/capture/local-auth.mjs" "$PROD_BASE_URL" "$TUTORIAL_AUTH_STATE" >> "$log"

# Draft contracts a take creates are demo junk: delete them after every run,
# pass or fail, so the next take's contract list is clean.
delete_takes_drafts() {
  local ids
  ids=$(grep -oE 'draft_contract_id=[0-9a-f-]{36}' "$log" | cut -d= -f2 | sort -u || true)
  if [[ -n "$ids" ]]; then
    # shellcheck disable=SC2086
    # A take that moved its contract past DRAFT (tutorial 11 signs it) leaves it for the
    # scenario's own off-camera cleanup on its next run; that is not a failed take.
    node "$tut/capture/local-delete-drafts.mjs" "$TUTORIAL_SEED_MANIFEST" "$backend_url" $ids 2>&1 | tee -a "$log" || true
  fi
}
trap delete_takes_drafts EXIT

echo "== proof (validate-only) tutorial $id"
(
  cd "$repo/web"
  TUTORIAL_CAPTURE_VALIDATE_ONLY=1 node ../tutorials/capture/record-tutorial.mjs \
    "$id" "$narration" "$TUTORIAL_WORK_DIR/validate.webm" "$TUTORIAL_SPEECH_RATE"
) 2>&1 | tee -a "$log"
grep -q '^scenario_validation=passed$' "$log" || { echo "Proof failed; nothing recorded." >&2; exit 1; }
delete_takes_drafts

reset_to_snapshot
echo "== capture tutorial $id"
set +e
"$tut/capture-tutorial.sh" "$id" "$TUTORIAL_VOICE" "$TUTORIAL_SPEECH_RATE" 2>&1 | tee -a "$log"
capture_rc=${PIPESTATUS[0]}
set -e
raw_dir=$(grep -oE '^raw_video_dir=.*' "$log" | tail -1 | cut -d= -f2-)
final="$TUTORIAL_OUTPUT_DIR/$slug.mp4"
srt="$TUTORIAL_OUTPUT_DIR/$slug.srt"

reject() {
  local reason=$1
  if [[ -n "$raw_dir" && -d "$raw_dir" ]]; then
    { echo "# REJECTED $(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo; echo "$reason"; } > "$raw_dir/REJECTED.md"
    echo "raw take kept for review: $raw_dir"
  fi
  echo "tutorial $id REJECTED: $reason" >&2
  exit 1
}
(( capture_rc == 0 )) || reject "capture-tutorial.sh exited $capture_rc (see $log)"

echo "== QA gates"
set +e
"$tut/qa-gates.sh" "$final" "$srt" "$tut/qa/$id-contact-sheet.jpg" 2>&1 | tee "$tut/qa/$id-qa.txt"
qa_rc=${PIPESTATUS[0]}
set -e
(( qa_rc == 0 )) || reject "QA gates failed: $(grep FAIL "$tut/qa/$id-qa.txt" | tr '\n' ' ')"

# Only now, with the final approved by the gates, drop the intermediates.
trap - EXIT
delete_takes_drafts
cp "$log" "$tut/qa/$id-record.log"
"$tut/prune-tutorial-media.sh" "$id" "$slug"

echo "tutorial=$id"
echo "output=$final"
echo "subtitles=$srt"
echo "contact_sheet=$tut/qa/$id-contact-sheet.jpg  <- review it"

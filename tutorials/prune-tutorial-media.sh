#!/usr/bin/env bash
# Remove a tutorial's intermediates once its final video has PASSED the QA gates.
#
#   tutorials/prune-tutorial-media.sh <id> <slug>                # raw takes, silent webm, work/<id>
#   tutorials/prune-tutorial-media.sh --silent-only <id> <slug>  # just output/<slug>-silent.webm
#
# Removes:  tutorials/raw/tutorial-<id>-*   tutorials/output/<slug>-silent.webm   tutorials/work/<id>
# Keeps:    the final MP4, the .srt, the narration text, the generated audio, QA evidence.
#
# Never call this for a failed or rejected take: its raw capture is the review
# material until a retake passes. Every target is resolved with realpath and
# must sit strictly inside tutorials/raw/ or tutorials/work/, or be exactly
# tutorials/output/<slug>-silent.webm; anything else is refused.
set -euo pipefail

silent_only=0
if [[ "${1:-}" == "--silent-only" ]]; then silent_only=1; shift; fi
id=${1:-}
slug=${2:-}
if ! [[ "$id" =~ ^[0-9]{2}$ ]]; then echo "refused: tutorial id must be two digits, got '$id'" >&2; exit 2; fi
if ! [[ "$slug" =~ ^${id}-[a-z0-9-]+$ ]]; then echo "refused: slug '$slug' must start with '$id-' and be kebab-case" >&2; exit 2; fi

repo=$(realpath "$(dirname "$0")/..")
if [[ -z "$repo" || "$repo" == "/" || ! -d "$repo/.git" ]]; then echo "refused: repo root '$repo' is not a git checkout" >&2; exit 2; fi
raw_root="$repo/tutorials/raw"
work_root="$repo/tutorials/work"
silent_file="$repo/tutorials/output/$slug-silent.webm"

remove() {
  local target=$1 real
  [[ -n "$target" ]] || { echo "refused: empty target" >&2; return 1; }
  [[ -e "$target" || -L "$target" ]] || return 0
  real=$(realpath "$target")
  case "$real" in
    "$raw_root"/?*|"$work_root"/?*|"$silent_file") ;;
    *) echo "refused: $real is outside tutorials/raw, tutorials/work and the silent webm" >&2; return 1 ;;
  esac
  if [[ "$real" == "$raw_root" || "$real" == "$work_root" || "$real" == "$repo" || "$real" == "/" ]]; then
    echo "refused: $real is a root" >&2; return 1
  fi
  rm -rf -- "$real"
  echo "removed $real"
}

remove "$silent_file"
(( silent_only )) && exit 0

shopt -s nullglob
for dir in "$raw_root"/tutorial-"$id"-*; do remove "$dir"; done
remove "$work_root/$id"

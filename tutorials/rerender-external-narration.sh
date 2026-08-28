#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <generated-audio-directory> <output-directory>" >&2
  exit 2
fi

task_audio_dir=$1
task_output_dir=$2
task_root=$(cd "$(dirname "$0")/.." && pwd)

for task_dir in "$task_audio_dir" "$task_output_dir"; do
  if [[ ! -d "$task_dir" ]]; then
    echo "Directory not found: $task_dir" >&2
    exit 2
  fi
done

task_count=0
while IFS= read -r task_silent; do
  task_stem=$(basename "$task_silent" -silent.webm)
  task_narration="$task_root/tutorials/narration/${task_stem}.txt"
  task_final="$task_output_dir/${task_stem}.mp4"
  task_audio=''

  for task_extension in wav mp3 m4a aac flac; do
    task_candidate="$task_audio_dir/${task_stem}.${task_extension}"
    if [[ -f "$task_candidate" ]]; then
      if [[ -n "$task_audio" ]]; then
        echo "More than one audio file found for $task_stem; keep only one supported format." >&2
        exit 1
      fi
      task_audio=$task_candidate
    fi
  done

  if [[ ! -f "$task_narration" ]]; then
    echo "Narration not found for $task_stem" >&2
    exit 1
  fi
  if [[ -z "$task_audio" ]]; then
    echo "Generated audio not found for $task_stem in $task_audio_dir" >&2
    exit 1
  fi

  TUTORIAL_TTS_PROVIDER=external TUTORIAL_AUDIO_FILE="$task_audio" \
    "$task_root/tutorials/render-tutorial.sh" \
      "$task_silent" "$task_narration" "$task_final"
  task_count=$((task_count + 1))
done < <(find "$task_output_dir" -maxdepth 1 -type f -name '*-silent.webm' -print | sort)

if (( task_count == 0 )); then
  echo "No silent tutorial sources found in $task_output_dir" >&2
  exit 1
fi

echo "rerendered=$task_count"
echo "audio_directory=$task_audio_dir"
echo "output_directory=$task_output_dir"

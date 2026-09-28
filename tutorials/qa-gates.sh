#!/usr/bin/env bash
# The walkthrough skill's seven QA gates, run against a final tutorial in
# tutorials/output/ (the skill's wt-qa.sh expects tutorials/approved/<ID>/).
#
#   tutorials/qa-gates.sh <final.mp4> <subtitles.srt> <contact-sheet.jpg>
#
# Exit 0 only when every gate passes. Gate 5 writes the contact sheet: look at it.
set -uo pipefail

mp4=${1:?usage: qa-gates.sh <final.mp4> <subtitles.srt> <contact-sheet.jpg>}
srt=${2:?usage}
sheet=${3:?usage}
failed=0
ok()  { echo "  PASS  $*"; }
bad() { echo "  FAIL  $*" >&2; failed=1; }
mkdir -p "$(dirname "$sheet")"
echo "QA $(basename "$mp4")"

# 1 readable, longer than 3 s
dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$mp4" 2>/dev/null | head -1)
if [[ -z "$dur" ]]; then bad "gate 1: ffprobe cannot read $mp4"; exit 1; fi
if python3 -c "import sys; sys.exit(0 if $dur > 3 else 1)"; then ok "gate 1 readable, ${dur}s"; else bad "gate 1: duration ${dur}s <= 3s"; fi

# 2 full decode is clean
err=$(ffmpeg -v error -i "$mp4" -f null - 2>&1 | head -5)
if [[ -z "$err" ]]; then ok "gate 2 decodes clean"; else bad "gate 2 decode errors: $err"; fi

# 3 video, audio and subtitle streams
codecs=$(ffprobe -v error -show_entries stream=codec_type,codec_name -of csv=p=0 "$mp4" | tr '\n' ' ')
if [[ "$codecs" == *"h264,video"* && "$codecs" == *"aac,audio"* ]]; then ok "gate 3 streams: $codecs"; else bad "gate 3: streams $codecs"; fi

# 4 audio/video durations within 1.5 s
vd=$(ffprobe -v error -select_streams v:0 -show_entries stream=duration -of csv=p=0 "$mp4" | head -1)
ad=$(ffprobe -v error -select_streams a:0 -show_entries stream=duration -of csv=p=0 "$mp4" | head -1)
if python3 -c "import sys; sys.exit(0 if abs($vd - $ad) <= 1.5 else 1)"; then ok "gate 4 a/v aligned (v=$vd a=$ad)"; else bad "gate 4: a/v drift v=$vd a=$ad"; fi

# 5 contact sheet, 30 tiles across the whole clip
fps=$(python3 -c "print(max(0.05, 30.0 / $dur))")
ffmpeg -v error -y -i "$mp4" -vf "fps=$fps,scale=384:-2,tile=6x5" -frames:v 1 "$sheet" 2>/dev/null
if [[ -s "$sheet" && $(stat -f%z "$sheet") -gt 8000 ]]; then ok "gate 5 contact sheet $sheet  <- LOOK AT THIS"; else bad "gate 5: contact sheet missing or trivial"; fi

# 6 subtitles parse and end within the video (+0.5 s, as the skill's gate)
if [[ -f "$srt" ]]; then
  last=$(grep -oE '[0-9]{2}:[0-9]{2}:[0-9]{2},[0-9]{3}' "$srt" | tail -1)
  cues=$(grep -cE '^[0-9]+$' "$srt")
  if [[ -n "$last" ]]; then
    ls=$(python3 -c "h,m,s='$last'.replace(',','.').split(':'); print(float(h)*3600+float(m)*60+float(s))")
    if python3 -c "import sys; sys.exit(0 if $ls <= $dur + 0.5 else 1)"; then ok "gate 6 srt ok ($cues cues, last ends ${ls}s)"; else bad "gate 6: last cue ${ls}s overruns ${dur}s"; fi
  else bad "gate 6: srt has no timecodes"; fi
else bad "gate 6: missing $srt"; fi

# 7 median luma is neither black nor white (limited range: 16..235)
yavg=$(ffmpeg -v error -i "$mp4" -vf "select='not(mod(n\,30))',signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-" -f null - 2>/dev/null \
  | grep -oE 'YAVG=[0-9.]+' | cut -d= -f2 | sort -n | awk '{a[NR]=$1} END{if(NR)print a[int(NR/2)+1]}')
if [[ -n "$yavg" ]] && python3 -c "import sys; sys.exit(0 if 18 < $yavg < 233 else 1)"; then ok "gate 7 luma $yavg"; else bad "gate 7: luma ${yavg:-none}"; fi

echo
if (( failed == 0 )); then echo "ALL GATES PASSED"; exit 0; fi
echo "QA FAILED" >&2; exit 1

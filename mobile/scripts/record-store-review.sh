#!/usr/bin/env bash
# Records the Play Store review videos: one MP4 per scenario, filmed on the
# attached Android phone while integration_test/store_review_test.dart drives the
# real app.
#
#   mobile/scripts/record-store-review.sh <defines.json> [renter|manager] [scenario ...]
#
# <defines.json> is a private file kept outside git. Keys:
#   API_BASE_URL                      e.g. https://rentaxis.uaenorth.cloudapp.azure.com/api
#   AHMED_EMAIL / AHMED_PASSWORD      renter with an active lease and cleared cheques
#   FATIMA_EMAIL / FATIMA_PASSWORD    renter with a returned cheque and a penalty
#   RAJESH_EMAIL / RAJESH_PASSWORD    renter in the renewal window
#   SARA_EMAIL / SARA_PASSWORD        renter with a contract waiting for acceptance
#   MANAGER_EMAIL / MANAGER_PASSWORD  property manager of the same organisation
#   SCAN_LEASE_ID                     lease whose REGISTERED cheques the scan videos attach to
#
# With no app, both apps run; with no scenario, every scenario of the app runs.
# Videos land in mobile/apps/<app>/build/store-review/<scenario>.mp4.
set -euo pipefail

defines=${1:?usage: record-store-review.sh <defines.json> [renter|manager] [scenario ...]}
defines=$(cd "$(dirname "$defines")" && pwd)/$(basename "$defines")
[ -f "$defines" ] || { echo "no such defines file: $defines" >&2; exit 1; }
shift
apps=(renter manager)
if [ $# -gt 0 ]; then apps=("$1"); shift; fi

renter_scenarios=(sign-in-and-home offers payments-and-receipts returned-cheque-and-penalties
  contract-acceptance renewal maintenance-ticket facilities-and-parking gate-pass
  notifications marketplace profile-arabic-and-account)
manager_scenarios=(sign-in-and-dashboard portfolio maintenance-tickets cheque-scan-payee-check
  cheque-scan-multi profile-arabic-and-account)

mobile_dir=$(cd "$(dirname "$0")/.." && pwd)
serial=$(adb get-serialno) || { echo "no Android device attached (adb devices)" >&2; exit 1; }
# screenrecord stops on its own at 180 s; every scenario is written to finish well inside it.
limit=180

record() {
  local app=$1 scenario=$2 out_dir=$3 log
  log=$(mktemp)
  local remote=/sdcard/store-review-$app-$scenario.mp4 rec_pid=
  echo "== $app / $scenario"
  # The test prints a start marker once the app is up and a stop marker when the
  # scenario is done, so the video holds the scenario only, not install and launch.
  (cd "$mobile_dir/apps/$app" && flutter drive --profile -d "$serial" \
    --driver=test_driver/integration_test.dart \
    --target=integration_test/store_review_test.dart \
    --dart-define-from-file="$defines" \
    --dart-define=SCENARIO="$scenario" 2>&1) | while IFS= read -r line; do
      echo "$line" >>"$log"
      case $line in
        *'[store-review] record-start'*)
          # Granted before the app asks, so no system prompt lands in the video.
          # A no-op for a permission the app doesn't declare.
          for perm in POST_NOTIFICATIONS CAMERA; do
            adb shell pm grant "com.rentaxis.$app" android.permission.$perm </dev/null 2>/dev/null || true
          done
          # stdin from /dev/null: otherwise adb reads this loop's pipe, eating the
          # stop marker and truncating the video.
          adb shell screenrecord --time-limit $limit "$remote" </dev/null &
          rec_pid=$!
          ;;
        *'[store-review] record-stop'*)
          adb shell pkill -INT screenrecord </dev/null || true
          [ -n "$rec_pid" ] && wait "$rec_pid" || true
          ;;
      esac
    done
  if ! grep -q 'All tests passed' "$log"; then
    adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
    echo "   FAILED, log: $log" >&2
    return 1
  fi
  sleep 1 # let screenrecord finish writing the file
  adb pull "$remote" "$out_dir/$scenario.mp4" >/dev/null
  adb shell rm -f "$remote"
  rm -f "$log"
  echo "   $out_dir/$scenario.mp4"
}

failed=0
for app in "${apps[@]}"; do
  case $app in
    renter) all=("${renter_scenarios[@]}") ;;
    manager) all=("${manager_scenarios[@]}") ;;
    *) echo "unknown app: $app" >&2; exit 1 ;;
  esac
  if [ $# -gt 0 ]; then scenarios=("$@"); else scenarios=("${all[@]}"); fi
  out_dir="$mobile_dir/apps/$app/build/store-review"
  mkdir -p "$out_dir"
  for scenario in "${scenarios[@]}"; do
    record "$app" "$scenario" "$out_dir" || failed=$((failed + 1))
  done
done
[ "$failed" -eq 0 ] || { echo "$failed scenario(s) failed" >&2; exit 1; }

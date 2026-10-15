#!/usr/bin/env bash
# Actual external process death followed by real packaged JS/HTML recovery.
set -euo pipefail
mkdir -p ui-screenshots
solvit_runner=dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner
solvit_class=dev.solvit.app.WorkspaceCloneProcessDeathTest
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 15s adb reverse tcp:19443 tcp:19443
for solvit_scenario in DOWNLOADING FILE CONFLICT METADATA COMMITTED CLEANUP; do
  solvit_prefix="ui-screenshots/clone-process-death-$solvit_scenario"
  timeout 15s adb shell run-as dev.solvit.app rm -f no_backup/clone-recovery-ready
  timeout --signal=TERM --kill-after=10s 90s adb shell am instrument -w \
    -e class "$solvit_class#prepareActualCloneAndWaitForExternalKill" \
    -e recoveryScenario "$solvit_scenario" "$solvit_runner" > "$solvit_prefix-prepare.txt" 2>&1 &
  solvit_prepare_pid=$!
  solvit_ready=false
  for solvit_tick in $(seq 1 60); do
    if timeout 5s adb shell run-as dev.solvit.app cat no_backup/clone-recovery-ready 2>/dev/null | tr -d '\r' | grep -Fx "$solvit_scenario"; then
      solvit_ready=true;break
    fi
    if ! kill -0 "$solvit_prepare_pid" 2>/dev/null; then cat "$solvit_prefix-prepare.txt";exit 1;fi
    sleep 1
  done
  test "$solvit_ready" = true
  timeout 5s adb shell pidof dev.solvit.app | tee "$solvit_prefix-pid-before.txt"
  test -s "$solvit_prefix-pid-before.txt"
  timeout 15s adb shell am force-stop dev.solvit.app
  wait "$solvit_prepare_pid" || true
  timeout 5s adb shell pidof dev.solvit.app > "$solvit_prefix-pid-after.txt" || true
  test ! -s "$solvit_prefix-pid-after.txt"
  if grep -F 'OK (' "$solvit_prefix-prepare.txt"; then echo 'Clone prepare ended normally instead of being killed';exit 1;fi
  if [ "$solvit_scenario" = DOWNLOADING ]; then
    curl --fail --silent --cacert "${RUNNER_TEMP:?}/solvit-clone-git-http/cert.pem" https://localhost:19443/control/clone-death-release
  fi
  timeout --signal=TERM --kill-after=10s 120s adb shell am instrument -w \
    -e class "$solvit_class#verifyFreshProcessFilesShellAndExplicitHtmlRecovery" "$solvit_runner" \
    | tee "$solvit_prefix-verify.txt"
  grep -F 'OK (1 test)' "$solvit_prefix-verify.txt"
done

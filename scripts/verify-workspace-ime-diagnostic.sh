#!/usr/bin/env bash
# Stop at the first failure; preserve real touch/key events and full editor assertions.
set -euo pipefail
mkdir -p ui-screenshots
capture() {
  timeout 15s adb logcat -d -s WORKSPACE_IME_DIAG:I WORKSPACE_PAGE_DIAG:I TestRunner:I '*:S' > ui-screenshots/workspace-ime-logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
}
trap capture EXIT
timeout 90s adb install -r diagnostic-apk/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for iteration in $(seq 1 8); do
  echo "Workspace editor IME diagnostic iteration $iteration/8"
  timeout --signal=TERM --kill-after=10s 120s adb shell am instrument -w \
    -e class 'dev.solvit.app.WorkspaceHtmlPageTest#htmlCreatesBindsBrowsesAndSavesThroughActualNativeConfirmationAndRejectsStaleFiles' \
    dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "ui-screenshots/workspace-ime-iteration-$iteration.txt"
  grep -F 'OK (1 test)' "ui-screenshots/workspace-ime-iteration-$iteration.txt"
done

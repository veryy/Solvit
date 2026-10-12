#!/usr/bin/env bash
# Diagnostic mode only. Runs the original real QuickJS/storage/UI test unchanged.
set -euo pipefail
mkdir -p ui-screenshots
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for iteration in $(seq 1 20); do
  echo "Memory filter diagnostic iteration $iteration/20"
  timeout --signal=TERM --kill-after=10s 90s adb shell am instrument -w \
    -e class 'dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin' \
    dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "ui-screenshots/memory-filter-iteration-$iteration.txt"
  grep -F 'OK (1 test)' "ui-screenshots/memory-filter-iteration-$iteration.txt"
done

#!/usr/bin/env bash
# Keep capture and tests in one shell: emulator-runner runs each script line separately.
set -uo pipefail
./gradlew :app:connectedDebugAndroidTest --stacktrace
solvit_test_status=$?
adb pull /sdcard/Android/data/dev.solvit.app/files/ ui-screenshots || true
exit "$solvit_test_status"

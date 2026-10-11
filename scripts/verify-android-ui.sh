#!/usr/bin/env bash
# Keep capture and tests in one shell: emulator-runner runs each script line separately.
set -uo pipefail
mkdir -p ui-screenshots
solvit_exclusions=dev.solvit.app.SeedUpgradeConfigurationTest
if [ -f .signing/debug.keystore ]; then
  test -f ui-screenshots/previous.apk || exit 1
  printf '%s\n' '88684ed71af4c390217d4ee6f58e3a169d513e812744eb97af34318d71d5d631  ui-screenshots/previous.apk' | sha256sum --check || exit 1
  timeout 90s adb install -r ui-screenshots/previous.apk || exit 1
  timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
  timeout 180s adb shell am instrument -w -e class dev.solvit.app.SeedUpgradeConfigurationTest dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner | tee ui-screenshots/upgrade-seed.txt
  grep -F 'OK (1 test)' ui-screenshots/upgrade-seed.txt || exit 1
  rm ui-screenshots/previous.apk
else
  # Fork PRs have no signing secret and cannot install over the signed release.
  solvit_exclusions="$solvit_exclusions,dev.solvit.app.UpgradePersistenceTest"
fi
timeout --signal=TERM --kill-after=30s 12m ./gradlew :terminal-android:connectedDebugAndroidTest :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=$solvit_exclusions" --stacktrace
solvit_test_status=$?
if [ "$solvit_test_status" -ne 0 ]; then
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
  timeout 15s adb shell dumpsys activity activities > ui-screenshots/emulator-activity.txt || true
fi
timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
exit "$solvit_test_status"

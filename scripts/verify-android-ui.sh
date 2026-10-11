#!/usr/bin/env bash
# Keep capture and tests in one shell: emulator-runner runs each script line separately.
set -uo pipefail
mkdir -p ui-screenshots
solvit_exclusions=dev.solvit.app.SeedUpgradeConfigurationTest
if [ -f .signing/debug.keystore ]; then
  curl --fail --location --silent --show-error https://github.com/veryy/Solvit/releases/download/v0.7.0/solvit-debug.apk --output ui-screenshots/previous.apk || exit 1
  printf '%s\n' '88684ed71af4c390217d4ee6f58e3a169d513e812744eb97af34318d71d5d631  ui-screenshots/previous.apk' | sha256sum --check || exit 1
  ./gradlew :app:assembleDebugAndroidTest --stacktrace || exit 1
  adb install -r ui-screenshots/previous.apk || exit 1
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
  adb shell am instrument -w -e class dev.solvit.app.SeedUpgradeConfigurationTest dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner | tee ui-screenshots/upgrade-seed.txt
  grep -F 'OK (1 test)' ui-screenshots/upgrade-seed.txt || exit 1
  rm ui-screenshots/previous.apk
else
  # Fork PRs have no signing secret and cannot install over the signed release.
  solvit_exclusions="$solvit_exclusions,dev.solvit.app.UpgradePersistenceTest"
fi
./gradlew :terminal-android:connectedDebugAndroidTest :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=$solvit_exclusions" --stacktrace
solvit_test_status=$?
adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
exit "$solvit_test_status"

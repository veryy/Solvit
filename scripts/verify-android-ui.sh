#!/usr/bin/env bash
# Keep capture and tests in one shell: emulator-runner runs each script line separately.
set -uo pipefail
mkdir -p ui-screenshots
# Live credentials are required by the separate real-model acceptance workflow.
solvit_exclusions=dev.solvit.app.SeedUpgradeConfigurationTest,dev.solvit.app.RealModelAcceptanceTest,dev.solvit.app.WorkspaceImportProcessDeathTest,dev.solvit.app.WorkspaceBackupCleanupProcessDeathTest,dev.solvit.app.WorkspaceCloneProcessDeathTest
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
bash scripts/verify-workspace-import-process-death.sh
solvit_recovery_status=$?
if [ "$solvit_recovery_status" -ne 0 ]; then
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
  exit "$solvit_recovery_status"
fi
bash scripts/verify-workspace-backup-cleanup-process-death.sh
solvit_cleanup_status=$?
if [ "$solvit_cleanup_status" -ne 0 ]; then
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
  exit "$solvit_cleanup_status"
fi
bash scripts/verify-workspace-clone-process-death.sh
solvit_clone_status=$?
if [ "$solvit_clone_status" -ne 0 ]; then
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
  exit "$solvit_clone_status"
fi
case "${SOLVIT_UI_DIAGNOSTIC:-none}" in
  none) ;;
  memory-filter-stress)
    bash scripts/verify-memory-page-stress.sh
    solvit_diagnostic_status=$?
    if [ "$solvit_diagnostic_status" -ne 0 ]; then
      timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
      timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
      exit "$solvit_diagnostic_status"
    fi
    ;;
  *) echo 'Unknown UI diagnostic mode'; exit 1 ;;
esac
timeout --signal=TERM --kill-after=30s 12m ./gradlew :web-android:connectedDebugAndroidTest :terminal-android:connectedDebugAndroidTest :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notClass=$solvit_exclusions,dev.solvit.terminal.StdioProcessDeathTest" --stacktrace
solvit_test_status=$?
if [ "$solvit_test_status" -eq 0 ]; then
  # Keep the real authorization/queue/IME/role scenario at large text in the release gate.
  # This runs on the same installed APK; its screenshots have a separate prefix.
  timeout --signal=TERM --kill-after=10s 180s adb shell am instrument -w \
    -e class dev.solvit.app.ChatInteractionDeviceTest#inlineRememberedApprovalAndEditableQueueInterruptThenNewChatKeepTheActualRole \
    -e chatFontScale 1.5 \
    dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee ui-screenshots/chat-large-text.txt
  solvit_test_status=$?
  if [ "$solvit_test_status" -eq 0 ]; then
    grep -F 'OK (1 test)' ui-screenshots/chat-large-text.txt >/dev/null || solvit_test_status=1
  fi
fi
if [ "$solvit_test_status" -eq 0 ]; then
  SOLVIT_STDIO_DEATH_OUTPUT=ui-screenshots/stdio-process-death bash scripts/verify-stdio-process-death.sh
  solvit_test_status=$?
fi
if [ "$solvit_test_status" -ne 0 ]; then
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
  timeout 15s adb shell dumpsys activity activities > ui-screenshots/emulator-activity.txt || true
fi
timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
exit "$solvit_test_status"

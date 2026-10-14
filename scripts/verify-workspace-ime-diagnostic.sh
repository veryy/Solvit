#!/usr/bin/env bash
# Stop at the first failure; preserve the original scenario's actions and assertions.
set -euo pipefail
case "${SOLVIT_DIAGNOSTIC_CASE:-workspace-editor}" in
  workspace-editor) solvit_diagnostic_test='dev.solvit.app.WorkspaceHtmlPageTest#htmlCreatesBindsBrowsesAndSavesThroughActualNativeConfirmationAndRejectsStaleFiles' ;;
  chat-interaction) solvit_diagnostic_test='dev.solvit.app.ChatInteractionDeviceTest#inlineRememberedApprovalAndEditableQueueInterruptThenNewChatKeepTheActualRole' ;;
  language-settings) solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#languageSwitchesImmediatelyAndSurvivesRecreation' ;;
  *) echo 'Unknown diagnostic scenario'; exit 1 ;;
esac
solvit_diagnostic_repetitions=${SOLVIT_DIAGNOSTIC_REPETITIONS:-8}
[[ "$solvit_diagnostic_repetitions" =~ ^[1-8]$ ]] || exit 1
mkdir -p ui-screenshots
capture() {
  timeout 15s adb logcat -d -s WORKSPACE_IME_DIAG:I WORKSPACE_PAGE_DIAG:I TestRunner:I '*:S' > ui-screenshots/page-diagnostic-logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
}
trap capture EXIT
timeout 90s adb install -r diagnostic-apk/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for iteration in $(seq 1 "$solvit_diagnostic_repetitions"); do
  echo "${SOLVIT_DIAGNOSTIC_CASE:-workspace-editor} diagnostic iteration $iteration/$solvit_diagnostic_repetitions"
  timeout --signal=TERM --kill-after=10s 120s adb shell am instrument -w \
    -e class "$solvit_diagnostic_test" \
    dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "ui-screenshots/page-diagnostic-iteration-$iteration.txt"
  grep -F 'OK (1 test)' "ui-screenshots/page-diagnostic-iteration-$iteration.txt"
done

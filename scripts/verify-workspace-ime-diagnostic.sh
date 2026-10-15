#!/usr/bin/env bash
# Stop at the first failure; preserve the original scenario's actions and assertions.
set -euo pipefail
solvit_diagnostic_expected='OK (1 test)'
case "${SOLVIT_DIAGNOSTIC_CASE:-workspace-editor}" in
  workspace-editor) solvit_diagnostic_test='dev.solvit.app.WorkspaceHtmlPageTest#htmlCreatesBindsBrowsesAndSavesThroughActualNativeConfirmationAndRejectsStaleFiles' ;;
  workspace-startup) solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#configuredModelHidesSetupAndNewChatHasNoWorkspaceUntilCreation' ;;
  plugin-resume)
    solvit_diagnostic_test='dev.solvit.app.McpPageUiTest,dev.solvit.app.PluginPageUiTest#packagedTaskPageShowsSourcesAndUsesNativeCostConfirmationBeforeServiceControl,dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin,dev.solvit.app.PluginWebPageUiTest,dev.solvit.app.WorkspaceImportProgressHtmlTest'
    solvit_diagnostic_expected='OK (5 tests)' ;;
  chat-interaction) solvit_diagnostic_test='dev.solvit.app.ChatInteractionDeviceTest#inlineRememberedApprovalAndEditableQueueInterruptThenNewChatKeepTheActualRole' ;;
  language-settings) solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#languageSwitchesImmediatelyAndSurvivesRecreation' ;;
  file-imports)
    solvit_diagnostic_test='dev.solvit.app.ChatAttachmentWorkspaceHtmlTest,dev.solvit.app.WorkspaceImportHtmlTest,dev.solvit.app.ChatLayoutTest#actualAndroidTsProjectRulesRefreshWithoutChangingRoleOrCreatingUnboundWorkspace'
    solvit_diagnostic_expected='OK (3 tests)' ;;
  *) echo 'Unknown diagnostic scenario'; exit 1 ;;
esac
solvit_diagnostic_repetitions=${SOLVIT_DIAGNOSTIC_REPETITIONS:-8}
[[ "$solvit_diagnostic_repetitions" =~ ^[1-8]$ ]] || exit 1
mkdir -p ui-screenshots
capture() {
  timeout 15s adb logcat -d -s WORKSPACE_IME_DIAG:I WORKSPACE_PAGE_DIAG:I TestRunner:I '*:S' > ui-screenshots/page-diagnostic-logcat.txt || true
  # Keep native Chromium crashes as well as test assertions; filtered logs hide SIGSEGV.
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/page-diagnostic-native-logcat.txt || true
  timeout 15s adb shell dumpsys webviewupdate > ui-screenshots/page-diagnostic-webview-provider.txt || true
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
  grep -F "$solvit_diagnostic_expected" "ui-screenshots/page-diagnostic-iteration-$iteration.txt"
done

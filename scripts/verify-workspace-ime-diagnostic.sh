#!/usr/bin/env bash
# Stop at the first failure; preserve the original scenario's actions and assertions.
set -euo pipefail
solvit_diagnostic_expected='OK (1 test)'
case "${SOLVIT_DIAGNOSTIC_CASE:-workspace-editor}" in
  plugin-page-feedback) solvit_diagnostic_test='dev.solvit.app.PluginPageFeedbackDeviceTest'; solvit_diagnostic_expected='OK (2 tests)' ;;
  workspace-editor) solvit_diagnostic_test='dev.solvit.app.WorkspaceHtmlPageTest#htmlCreatesBindsBrowsesAndSavesThroughActualNativeConfirmationAndRejectsStaleFiles' ;;
  workspace-startup) solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#configuredModelHidesSetupAndNewChatHasNoWorkspaceUntilCreation' ;;
  workspace-startup-stress) solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#configuredModelHidesSetupAndNewChatHasNoWorkspaceUntilCreation' ;;
  workspace-startup-sequence)
    # Preserve the actual preceding WebView/PTY/memory scenarios in the failed full run.
    solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#workspaceTerminalRunsRealAndroidShellInTheBoundWorkspace,dev.solvit.app.ChatLayoutTest#interactiveTerminalUiKeepsEnvironmentAndDisplaysOutput,dev.solvit.app.ChatLayoutTest#memoryManagementEditsAndDeletesWithPluginDisabled,dev.solvit.app.ChatLayoutTest#editingOldQuestionCreatesEmptyBranchAndPrefillsDraftWithoutSending,dev.solvit.app.ChatLayoutTest#pluginPageCanReachBottomAndReturnToChat,dev.solvit.app.ChatLayoutTest#actualAndroidJsMemoryReachesPreparedModelRequestWithoutSearchToolCalls,dev.solvit.app.ChatLayoutTest#configuredModelHidesSetupAndNewChatHasNoWorkspaceUntilCreation'
    solvit_diagnostic_expected='OK (7 tests)' ;;
  workspace-user-lifecycle|workspace-user-lifecycle-stress) solvit_diagnostic_test='dev.solvit.app.WebViewUserLifecycleTest#workspaceRecreationUsesProductionDispatcherAndReleasesTheActualBrowser' ;;
  webview-plugin-host-control) solvit_diagnostic_test='dev.solvit.app.PluginHostLifecycleControlTest#minimalDocumentUsesProductionHostAndReleasesAfterRecreation' ;;
  plugin-page-ready) solvit_diagnostic_test='dev.solvit.app.PluginPageReadyStateTest#delayedReadyReceivesLatestStateWithoutEarlierScriptDelivery' ;;
  compose-lifecycle-dispatch) solvit_diagnostic_test='dev.solvit.app.ComposeLifecycleDispatchTest' ;;
  plugin-document-lifecycle) solvit_diagnostic_test='dev.solvit.app.PluginWebPageLifecycleTest' ;;
  plugin-native-entry)
    solvit_diagnostic_test='dev.solvit.app.PluginPageUiTest'
    solvit_diagnostic_expected='OK (6 tests)' ;;
  plugin-resume)
    solvit_diagnostic_test='dev.solvit.app.McpPageUiTest,dev.solvit.app.PluginPageUiTest#packagedTaskPageShowsSourcesAndUsesNativeCostConfirmationBeforeServiceControl,dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin,dev.solvit.app.PluginWebPageUiTest,dev.solvit.app.WorkspaceImportProgressHtmlTest'
    solvit_diagnostic_expected='OK (5 tests)' ;;
  chat-interaction) solvit_diagnostic_test='dev.solvit.app.ChatInteractionDeviceTest#inlineRememberedApprovalAndEditableQueueInterruptThenNewChatKeepTheActualRole' ;;
  queued-ui-scroll)
    solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#replyBranchCopiesToolTurnAndRoleAndWorkspaceWithoutChangingOriginal,dev.solvit.app.ChatLayoutTest#applyingRoleInSettingsPersistsAndNewChatInheritsItsPluginWhitelist,dev.solvit.app.ChatLayoutTest#wideDrawerOpensSettingsAndLastSettingsRowCanBeSeen'
    solvit_diagnostic_expected='OK (3 tests)' ;;
  queued-ui-follow-up)
    solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#nativeProtocolSettingsSaveOptionsAndReopenThroughProviderDeclarations,dev.solvit.app.ChatLayoutTest#actualChatShowsPartialTokensBeforeCompletionAndFollowsLongReplyAboveKeyboard,dev.solvit.app.ChatLayoutTest#languageSwitchesImmediatelyAndSurvivesRecreation,dev.solvit.app.PdfChatDeviceTest#pickerSelectedScanPreviewHttpRetryReopenAndEditPreserveTheSameSelection'
    solvit_diagnostic_expected='OK (4 tests)' ;;
  chat-branches)
    solvit_diagnostic_test='dev.solvit.app.ChatAttachmentsDeviceTest#pickerImportsImagesAndTextThenHttpSendAndReopenPreserveTheirContent,dev.solvit.app.ChatLayoutTest#editingOldQuestionCreatesEmptyBranchAndPrefillsDraftWithoutSending'
    solvit_diagnostic_expected='OK (2 tests)' ;;
  settings-theme) solvit_diagnostic_test='dev.solvit.app.ChatLayoutTest#settingsIndexOpensThemeAndRetainsChoiceWhenReturning' ;;
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
  timeout 15s adb logcat -d -s WORKSPACE_IME_DIAG:I WORKSPACE_PAGE_DIAG:I SolvitPdfFixture:I PluginPageFixture:I TestRunner:I '*:S' > ui-screenshots/page-diagnostic-logcat.txt || true
  # Keep native Chromium crashes as well as test assertions; filtered logs hide SIGSEGV.
  timeout 15s adb logcat -d > ui-screenshots/page-diagnostic-native-logcat.txt || true
  timeout 15s adb shell dumpsys webviewupdate > ui-screenshots/page-diagnostic-webview-provider.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
}
trap capture EXIT
timeout 90s adb install -r diagnostic-apk/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for iteration in $(seq 1 "$solvit_diagnostic_repetitions"); do
  echo "${SOLVIT_DIAGNOSTIC_CASE:-workspace-editor} diagnostic iteration $iteration/$solvit_diagnostic_repetitions"
  solvit_iteration_timeout=120s
  solvit_recreations=3
  case "${SOLVIT_DIAGNOSTIC_CASE:-}" in
    workspace-startup-sequence|queued-ui-follow-up|plugin-native-entry) solvit_iteration_timeout=300s ;;
    workspace-startup-stress|workspace-user-lifecycle-stress|webview-plugin-host-control) solvit_iteration_timeout=300s; solvit_recreations=30 ;;
  esac
  timeout --signal=TERM --kill-after=10s "$solvit_iteration_timeout" adb shell am instrument -w \
    -e class "$solvit_diagnostic_test" \
    -e workspaceRecreations "$solvit_recreations" \
    -e branchHistoryAtEnd "$([ "${SOLVIT_DIAGNOSTIC_CASE:-}" = chat-branches ] && echo true || echo false)" \
    dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "ui-screenshots/page-diagnostic-iteration-$iteration.txt"
  grep -F "$solvit_diagnostic_expected" "ui-screenshots/page-diagnostic-iteration-$iteration.txt"
done

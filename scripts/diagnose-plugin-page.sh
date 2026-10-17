#!/usr/bin/env bash
# Focused reproduction only; never replaces full release gates.
set -uo pipefail
mkdir -p ui-screenshots
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = git-clone-recovery ]; then
  bash scripts/verify-workspace-clone-process-death.sh
  solvit_clone_status=$?
  timeout 15s adb logcat -d -t 1500 > ui-screenshots/emulator-logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
  exit "$solvit_clone_status"
fi
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
solvit_memory_label_bounds=false
case "${SOLVIT_PAGE_DIAGNOSTIC:-memory-click}" in
  memory-labels) solvit_diagnostic_class='dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin'; solvit_memory_label_bounds=true ;;
  memory-click) solvit_diagnostic_class='dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin' ;;
  plugin-document-lifecycle) solvit_diagnostic_class='dev.solvit.app.PluginWebPageLifecycleTest' ;;
  model-settings) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#nativeProtocolSettingsSaveOptionsAndReopenThroughProviderDeclarations' ;;
  model-task) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#modelProfilesUiSavesAndAssignsASeparateToolModel' ;;
  skills-documents) solvit_diagnostic_class='dev.solvit.app.SkillsHtmlPageTest' ;;
  mcp-page) solvit_diagnostic_class='dev.solvit.app.McpPageUiTest' ;;
  project-rules) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#actualAndroidTsProjectRulesRefreshWithoutChangingRoleOrCreatingUnboundWorkspace' ;;
  chat-role-sequence) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#javascriptPluginUsesPersistentPtyAndDisableClosesItsProcess,dev.solvit.app.ChatLayoutTest#largeNativeTranscriptReopensAndBranchesThroughRealAndroidCursorWindows,dev.solvit.app.ChatLayoutTest#roleAppliedThroughSettingsReachesProviderAndNewChatRequest' ;;
  chat-role) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#roleAppliedThroughSettingsReachesProviderAndNewChatRequest' ;;
  chat-branch) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#editingOldQuestionCreatesEmptyBranchAndPrefillsDraftWithoutSending' ;;
  terminal-ui) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#interactiveTerminalUiKeepsEnvironmentAndDisplaysOutput' ;;
  chat-stream) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#actualChatShowsPartialTokensBeforeCompletionAndFollowsLongReplyAboveKeyboard' ;;
  chat-attachments) solvit_diagnostic_class='dev.solvit.app.ChatAttachmentsDeviceTest' ;;
  chat-keyboard) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#startupFocusesInputAndFloatingComposerStaysAboveRealKeyboard' ;;
  git-credentials) solvit_diagnostic_class='dev.solvit.app.GitHtmlPageTest#actualHtmlCredentialControlSavesOnlyNativeEncryptedFieldsAndSupportsRemoveAndCancel' ;;
  git-queries) solvit_diagnostic_class='dev.solvit.app.GitHtmlPageTest#actualQuickJsGitToolsReadBoundRepositoryAndDenyUnapprovedOrReboundCalls' ;;
  git-workbench) solvit_diagnostic_class='dev.solvit.app.GitHtmlPageTest' ;;
  workspace-files) solvit_diagnostic_class='dev.solvit.app.WorkspaceHtmlPageTest,dev.solvit.app.ChatLayoutTest#workspaceTerminalRunsRealAndroidShellInTheBoundWorkspace' ;;
  file-diff) solvit_diagnostic_class='dev.solvit.app.HistoricalDiffHtmlTest' ;;
  chat-interaction) solvit_diagnostic_class='dev.solvit.app.ChatInteractionDeviceTest' ;;
  *) exit 2 ;;
esac
solvit_diagnostic_iterations=1
solvit_diagnostic_expected='OK (1 test)'
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = file-diff ] || [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-interaction ] || [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = workspace-files ]; then solvit_diagnostic_expected='OK (2 tests)'; fi
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = git-workbench ]; then solvit_diagnostic_expected='OK (3 tests)'; fi
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-role ]; then solvit_diagnostic_iterations=10; fi
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-keyboard ] || [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-attachments ]; then solvit_diagnostic_iterations=3; fi
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-role-sequence ]; then
  solvit_diagnostic_iterations=10
  solvit_diagnostic_expected='OK (3 tests)'
fi
for ((solvit_diagnostic_iteration=1; solvit_diagnostic_iteration<=solvit_diagnostic_iterations; solvit_diagnostic_iteration++)); do
  solvit_diagnostic_result="ui-screenshots/result-${solvit_diagnostic_iteration}.txt"
  timeout 180s adb shell am instrument -w -e class "$solvit_diagnostic_class" -e verifyMemoryLabelBounds "$solvit_memory_label_bounds" dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$solvit_diagnostic_result"
  if ! grep -F "$solvit_diagnostic_expected" "$solvit_diagnostic_result" >/dev/null; then
    # Preserve blocked ART threads before collecting logs; do not hide the failing result.
    solvit_diagnostic_pid=$(timeout 5s adb shell pidof dev.solvit.app | tr -d '\r')
    if [[ "$solvit_diagnostic_pid" =~ ^[0-9]+$ ]]; then
      timeout 10s adb shell run-as dev.solvit.app kill -3 "$solvit_diagnostic_pid" || true
    fi
    timeout 90s adb bugreport ui-screenshots/timeout-bugreport.zip || true
  fi
  timeout 15s adb logcat -d > ui-screenshots/logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
  cp "$solvit_diagnostic_result" ui-screenshots/result.txt
  grep -F "$solvit_diagnostic_expected" "$solvit_diagnostic_result" || exit 1
done

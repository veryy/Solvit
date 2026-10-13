#!/usr/bin/env bash
# Focused reproduction only; never replaces full release gates.
set -uo pipefail
mkdir -p ui-screenshots
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
case "${SOLVIT_PAGE_DIAGNOSTIC:-memory-click}" in
  memory-click) solvit_diagnostic_class='dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin' ;;
  model-settings) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#nativeProtocolSettingsSaveOptionsAndReopenThroughProviderDeclarations' ;;
  model-task) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#modelProfilesUiSavesAndAssignsASeparateToolModel' ;;
  skills-documents) solvit_diagnostic_class='dev.solvit.app.SkillsHtmlPageTest' ;;
  chat-role-sequence) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#javascriptPluginUsesPersistentPtyAndDisableClosesItsProcess,dev.solvit.app.ChatLayoutTest#largeNativeTranscriptReopensAndBranchesThroughRealAndroidCursorWindows,dev.solvit.app.ChatLayoutTest#roleAppliedThroughSettingsReachesProviderAndNewChatRequest' ;;
  chat-role) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#roleAppliedThroughSettingsReachesProviderAndNewChatRequest' ;;
  chat-branch) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#editingOldQuestionCreatesEmptyBranchAndPrefillsDraftWithoutSending' ;;
  terminal-ui) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#interactiveTerminalUiKeepsEnvironmentAndDisplaysOutput' ;;
  chat-stream) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#actualChatShowsPartialTokensBeforeCompletionAndFollowsLongReplyAboveKeyboard' ;;
  workspace-files) solvit_diagnostic_class='dev.solvit.app.WorkspaceHtmlPageTest' ;;
  *) exit 2 ;;
esac
solvit_diagnostic_iterations=1
solvit_diagnostic_expected='OK (1 test)'
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-role ]; then solvit_diagnostic_iterations=10; fi
if [ "${SOLVIT_PAGE_DIAGNOSTIC:-}" = chat-role-sequence ]; then
  solvit_diagnostic_iterations=10
  solvit_diagnostic_expected='OK (3 tests)'
fi
for ((solvit_diagnostic_iteration=1; solvit_diagnostic_iteration<=solvit_diagnostic_iterations; solvit_diagnostic_iteration++)); do
  solvit_diagnostic_result="ui-screenshots/result-${solvit_diagnostic_iteration}.txt"
  timeout 180s adb shell am instrument -w -e class "$solvit_diagnostic_class" dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$solvit_diagnostic_result"
  timeout 15s adb logcat -d > ui-screenshots/logcat.txt || true
  timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
  cp "$solvit_diagnostic_result" ui-screenshots/result.txt
  grep -F "$solvit_diagnostic_expected" "$solvit_diagnostic_result" || exit 1
done

#!/usr/bin/env bash
# Focused reproduction only; never replaces full release gates.
set -uo pipefail
mkdir -p ui-screenshots
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
case "${SOLVIT_PAGE_DIAGNOSTIC:-memory-click}" in
  memory-click) solvit_diagnostic_class='dev.solvit.app.PluginPageUiTest#packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin' ;;
  model-settings) solvit_diagnostic_class='dev.solvit.app.ChatLayoutTest#nativeProtocolSettingsSaveOptionsAndReopenThroughProviderDeclarations' ;;
  skills-documents) solvit_diagnostic_class='dev.solvit.app.SkillsHtmlPageTest' ;;
  workspace-files) solvit_diagnostic_class='dev.solvit.app.WorkspaceHtmlPageTest' ;;
  *) exit 2 ;;
esac
timeout 180s adb shell am instrument -w -e class "$solvit_diagnostic_class" dev.solvit.app.test/androidx.test.runner.AndroidJUnitRunner | tee ui-screenshots/result.txt
timeout 15s adb logcat -d > ui-screenshots/logcat.txt || true
timeout 30s adb pull /data/local/tmp/solvit-ui/ ui-screenshots || true
grep -F 'OK (1 test)' ui-screenshots/result.txt

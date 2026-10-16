"""Independent publication gate; a later successful command cannot hide a device failure."""
import json
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

MINIMUM = {'app': 82, 'web-android': 3, 'terminal-android': 25}

REQUIRED = {'app': {
    ('dev.solvit.app.ChatLayoutTest', 'settingsIndexOpensThemeAndRetainsChoiceWhenReturning'),
    ('dev.solvit.app.ChatInteractionDeviceTest', 'inlineRememberedApprovalAndEditableQueueInterruptThenNewChatKeepTheActualRole'),
    ('dev.solvit.app.PluginDetailsLayoutTest', 'disabledDetailsKeepPrimaryActionVisibleWithLargeTextAndLongPermissions'),
    ('dev.solvit.app.PluginDetailsLayoutTest', 'enabledDetailsOpenTheChosenPluginPageAndKeepUninstallSeparate'),
    ('dev.solvit.app.PluginPageFeedbackDeviceTest', 'failedOpenCanReopenInPlaceAndCloseTheRecoveredSession'),
    ('dev.solvit.app.PluginPageFeedbackDeviceTest', 'failedDispatchReopensOnlyAfterCleanupAndNeverReplaysTheWrite'),
    ('dev.solvit.app.WorkspaceFormDeviceTest', 'failedCreationKeepsNameAndAllowsRetryAfterShowingPendingState'),
    ('dev.solvit.app.WorkspaceFormDeviceTest', 'failedBindingKeepsChooserAndReenablesTheSelectedWorkspace'),
    ('dev.solvit.app.WorkspaceFormDeviceTest', 'successfulBindingClosesChooserWhenOnlyDirectoryReadFails'),
    ('dev.solvit.app.WorkspaceFormDeviceTest', 'closingPendingFormShowsBackgroundProgressAndKeepsDraftAfterFailure'),
    ('dev.solvit.app.PluginPageReadyStateTest', 'delayedReadyReceivesLatestStateWithoutEarlierScriptDelivery'),
    ('dev.solvit.app.PluginHostLifecycleControlTest', 'minimalDocumentUsesProductionHostAndReleasesAfterRecreation'),
    ('dev.solvit.app.WebViewUserLifecycleTest', 'workspaceRecreationUsesProductionDispatcherAndReleasesTheActualBrowser'),
}}

def verify(root: Path) -> dict[str, int]:
    counts = {}
    for module, minimum in MINIMUM.items():
        files = sorted((root / module / 'build/outputs/androidTest-results').rglob('TEST-*.xml'))
        if not files:
            raise ValueError(f'{module}: missing device reports')
        count = 0
        seen = set()
        for file in files:
            report = ET.parse(file).getroot()
            cases = list(report.iter('testcase'))
            if not cases or int(report.get('tests', '0')) != len(cases):
                raise ValueError(f'{module}: empty or inconsistent report')
            for suite in report.iter():
                if suite.tag in ('failure', 'error', 'skipped') or any(int(suite.get(k, '0')) for k in ('failures', 'errors', 'skipped')):
                    raise ValueError(f'{module}: failed, erroneous or skipped device test')
            seen.update((case.get('classname'), case.get('name')) for case in cases)
            count += len(cases)
        if REQUIRED.get(module, set()) - seen:
            raise ValueError(f'{module}: missing required device regression')
        if count < minimum:
            raise ValueError(f'{module}: expected at least {minimum} device tests, got {count}')
        counts[module] = count
    return counts

if __name__ == '__main__':
    result = verify(Path(sys.argv[1] if len(sys.argv) > 1 else '.'))
    destination = Path('ui-screenshots/device-report-summary.json')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))

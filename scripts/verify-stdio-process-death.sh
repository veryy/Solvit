#!/usr/bin/env bash
set -euo pipefail
solvit_package=dev.solvit.terminal.test
solvit_output=${SOLVIT_STDIO_DEATH_OUTPUT:-terminal-android/build/stdio-process-death}
mkdir -p "$solvit_output"
adb shell run-as "$solvit_package" rm -f files/stdio-process-death.json files/stdio-process-death.pending
timeout --signal=TERM --kill-after=5s 110s adb shell am instrument -w \
  -e class dev.solvit.terminal.StdioProcessDeathTest \
  "$solvit_package/androidx.test.runner.AndroidJUnitRunner" > "$solvit_output/instrumentation.txt" 2>&1 &
solvit_instrumentation=$!
trap 'kill "$solvit_instrumentation" 2>/dev/null || true; wait "$solvit_instrumentation" 2>/dev/null || true' EXIT
solvit_ready=false
for solvit_attempt in {1..45}; do
  if adb shell run-as "$solvit_package" cat files/stdio-process-death.json > "$solvit_output/identity.json" 2>/dev/null &&
    python3 - "$solvit_output/identity.json" <<'PY_IDENTITY'
import json, sys
x = json.load(open(sys.argv[1]))
assert x['fixture'] == 'solvit-native-stdio-death-v1'
pids = [x[k] for k in ('host', 'supervisor', 'worker', 'descendant')]
assert all(type(pid) is int and pid > 1 for pid in pids)
assert len(set(pids)) == 4
PY_IDENTITY
  then solvit_ready=true; break; fi
  sleep 1
done
test "$solvit_ready" = true
solvit_host=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["host"])' "$solvit_output/identity.json")
adb shell run-as "$solvit_package" cat "/proc/$solvit_host/cmdline" > "$solvit_output/host-cmdline.bin"
python3 - "$solvit_output/host-cmdline.bin" "$solvit_package" <<'PY_CMDLINE'
from pathlib import Path
import sys
assert Path(sys.argv[1]).read_bytes().split(b'\0', 1)[0].decode() == sys.argv[2], 'Refuse to kill an unrelated PID'
PY_CMDLINE
# Kill only the recorded JVM, rather than force-stop the entire Android package.
adb shell run-as "$solvit_package" /system/bin/kill -9 "$solvit_host"
wait "$solvit_instrumentation" 2>/dev/null || true
python3 - "$solvit_output/identity.json" "$solvit_output/result.json" "$solvit_package" <<'PY_VERIFY'
import json, subprocess, sys, time
x = json.load(open(sys.argv[1]))
package = sys.argv[3]
deadline = time.monotonic() + 10
observed = {}
while time.monotonic() < deadline:
    observed = {}
    for key in ('host', 'supervisor', 'worker', 'descendant'):
        result = subprocess.run(['adb', 'shell', 'run-as', package, 'cat', f'/proc/{x[key]}/stat'], capture_output=True)
        if result.returncode != 0:
            observed[key] = 'gone'
        else:
            state = result.stdout.decode().rsplit(') ', 1)[1].split()[0]
            observed[key] = state
    if all(value == 'gone' for value in observed.values()):
        break
    time.sleep(0.1)
assert all(value == 'gone' for value in observed.values()), f'Owned fixture PIDs were not reaped: {observed}'
report = {'fixture': x['fixture'], 'actualHostSigkill': True, 'pids': {key: x[key] for key in observed}, 'after': observed}
with open(sys.argv[2], 'w') as output:
    json.dump(report, output, indent=2)
print('Actual stdio host SIGKILL: JVM, supervisor, worker and descendant are gone')
PY_VERIFY

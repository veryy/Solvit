import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

test('clock reads fresh native local time for every context and tool invocation', async () => {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.clock.solvit.json', 'utf8'));
  assert.equal(pkg.manifest.apiVersion, 3);
  assert.deepEqual(pkg.manifest.permissions, []);
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox);
  let reads = 0;
  const ctx = { clock: { now: async () => ({ utc: `2030-01-01T00:00:0${reads++}Z`, localTime: '2030-01-01T08:00:00+08:00', timezone: 'Asia/Shanghai', timezoneOffsetMinutes: -480 }) } };
  assert.match(await sandbox.solvitPlugin.beforeModel({}, ctx), /00:00:00Z.*Asia\/Shanghai/);
  assert.match(await sandbox.solvitPlugin.beforeModel({}, ctx), /00:00:01Z/);
  assert.equal((await sandbox.solvitPlugin.tools.current_time({}, ctx)).utc, '2030-01-01T00:00:02Z');
  assert.equal(reads, 3);
});

test('older API 3 hosts without native clock still receive fresh UTC context', async () => {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.clock.solvit.json', 'utf8'));
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox);
  const context = await sandbox.solvitPlugin.beforeModel({}, {});
  assert.match(context, /设备当前时间/);
  assert.match(context, /"utc":"\d{4}-\d{2}-\d{2}T/);
  assert.match(context, /旧宿主未提供 Android 时区/);
});

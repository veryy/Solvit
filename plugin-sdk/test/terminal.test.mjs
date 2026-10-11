import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

test('terminal tools preserve exact approved input and require API 5', async () => {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.terminal.solvit.json', 'utf8'));
  assert.equal(pkg.manifest.apiVersion, 5);
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox);
  const calls = [];
  const ctx = { terminal: { call: async (operation, args) => { calls.push([operation, args]); return { id: 'pty-id' }; } } };
  for (const [name, operation, args] of [
    ['terminal_open', 'open', {shellId:'shell'}], ['terminal_status', 'status', {id:'pty-id'}],
    ['terminal_input', 'input', {id:'pty-id',text:'  printf "你好"\n'}],
    ['terminal_interrupt','interrupt',{id:'pty-id'}], ['terminal_close','close',{id:'pty-id'}],
  ]) {
    await sandbox.solvitPlugin.tools[name](args, ctx);
    assert.equal(calls.at(-1)[0], operation); assert.equal(calls.at(-1)[1], args);
  }
});

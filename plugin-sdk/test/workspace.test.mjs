import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

test('workspace package forwards approved parameters unchanged and keeps native operations explicit', async () => {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.workspace.solvit.json', 'utf8'));
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox);
  const seen = [];
  const ctx = { workspace: { call: async (operation, args) => { seen.push([operation, args]); return 'host-result'; } } };
  for (const [tool, operation, args] of [
    ['info','info',{}], ['read_file','read',{path:'src/a.js'}], ['write_file','write',{path:'src/a.js',text:'value'}],
    ['create_shell','createShell',{name:'Shell 2'}], ['shell_start','start',{shellId:'shell-id',command:'printf hello'}],
    ['shell_status','status',{id:'job-id'}], ['shell_stop','cancel',{id:'job-id'}],
  ]) {
    assert.equal(await sandbox.solvitPlugin.tools[tool](args, ctx), 'host-result');
    assert.equal(seen.at(-1)[0], operation); assert.equal(seen.at(-1)[1], args);
  }
  assert.equal(pkg.manifest.apiVersion, 12);
});

test('authoring package only submits drafts and exposes no install or enable tool', async () => {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.authoring.solvit.json', 'utf8'));
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox);
  let submitted;
  const args = { manifestJson:'{"id":"example.test"}',source:'globalThis.solvitPlugin={tools:{}}' };
  await sandbox.solvitPlugin.tools.draft_plugin(args, { plugins: { draft: async value => { submitted = value; return {id:'draft'}; } } });
  assert.equal(submitted.manifestJson,args.manifestJson); assert.equal(submitted.source,args.source);
  assert.deepEqual(Object.keys(sandbox.solvitPlugin.tools),['draft_plugin']);
});

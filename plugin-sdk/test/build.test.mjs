import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile, mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { buildPlugin } from '../scripts/build-plugin.mjs';

test('API 9 page declarations bundle callbacks and reject ambiguous or older manifests', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-pages-'));
  try {
    const entry = join(dir, 'entry.ts'), file = join(dir, 'manifest.json'), output = join(dir, 'output.json');
    await writeFile(entry, "export default {tools:{},pages:{manage: input=>({title:input.locale,nodes:[]})}};");
    const manifest = { id:'test.pages', name:'Pages', description:'Test', version:'1.0.0', apiVersion:9,
      permissions:[],dependencies:[],tools:[],pages:[{id:'manage',title:'管理'}] };
    for (const invalid of [ {...manifest,apiVersion:8}, {...manifest,pages:[...manifest.pages,...manifest.pages]},
      {...manifest,pages:[{id:'../escape',title:'Bad'}]}, {...manifest,pages:[{id:'manage',title:42}]} ]) {
      await writeFile(file, JSON.stringify(invalid));
      await assert.rejects(()=>buildPlugin(entry,file,output));
    }
    await writeFile(file, JSON.stringify(manifest)); await buildPlugin(entry,file,output);
    const sandbox = vm.createContext({}); vm.runInContext(JSON.parse(await readFile(output,'utf8')).source,sandbox);
    assert.equal(sandbox.solvitPlugin.pages.manage({locale:'ja',event:null}).title,'ja');
  } finally { await rm(dir,{recursive:true}); }
});

test('bundles TypeScript imports as standalone host-compatible JavaScript', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-sdk-'));
  try {
    const output = join(dir, 'echo.solvit.json');
    await buildPlugin(resolve('../plugin-examples/echo/index.ts'), resolve('../plugin-examples/echo/manifest.json'), output);
    const pkg = JSON.parse(await readFile(output, 'utf8'));
    const sandbox = vm.createContext({});
    vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
    assert.equal(pkg.manifest.id, 'example.echo');
    const response = await sandbox.solvitPlugin.tools.echo({ text: '你好' }, {});
    assert.deepEqual(JSON.parse(JSON.stringify(response)), { text: '你好' });
    await assert.rejects(() => sandbox.solvitPlugin.tools.echo({ text: 123 }, {}), /Invalid text/);
  } finally { await rm(dir, { recursive: true }); }
});

test('rejects Node dependencies unavailable inside Android QuickJS', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-sdk-'));
  try {
    const entry = join(dir, 'bad.ts');
    await writeFile(entry, "import fs from 'node:fs'; export default fs;");
    await assert.rejects(() => buildPlugin(entry, resolve('../plugin-examples/echo/manifest.json'), join(dir, 'bad.json')));
  } finally { await rm(dir, { recursive: true }); }
});

test('includes pure JavaScript package dependencies without runtime module loading', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-sdk-'));
  try {
    const dependency = join(dir, 'node_modules/pure-format');
    await mkdir(dependency, { recursive: true });
    await writeFile(join(dependency, 'package.json'), JSON.stringify({ name: 'pure-format', type: 'module', main: 'index.js' }));
    await writeFile(join(dependency, 'index.js'), 'export const format = value => `formatted:${value}`;');
    const entry = join(dir, 'entry.ts'), output = join(dir, 'package.json');
    await writeFile(entry, "import {format} from 'pure-format'; export default {tools:{echo: args => ({text:format(args.text)})}};");
    await buildPlugin(entry, resolve('../plugin-examples/echo/manifest.json'), output);
    const sandbox = vm.createContext({});
    vm.runInContext(JSON.parse(await readFile(output, 'utf8')).source, sandbox, { timeout: 1000 });
    assert.equal(sandbox.solvitPlugin.tools.echo({ text: 'dependency' }).text, 'formatted:dependency');
  } finally { await rm(dir, { recursive: true }); }
});

test('API 8 background hooks require an explicit default-off declaration', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-background-'));
  try {
    const entry = join(dir, 'entry.ts'), manifestFile = join(dir, 'manifest.json'), output = join(dir, 'output.json');
    await writeFile(entry, "export default {tools:{},afterTurn:async()=>{}};");
    const manifest = { id: 'test.background', name: 'Background', version: '1.0.0', apiVersion: 8,
      permissions: ['storage','model'], tools: [], hooks: ['afterTurn'], afterTurnEnabledSetting: 'autoSave',
      settings: [{key:'autoSave',kind:'boolean',defaultValue:'false'}] };
    for (const invalid of [
      {...manifest,apiVersion:7}, {...manifest,settings:[]}, {...manifest,afterTurnEnabledSetting:'missing'},
      {...manifest,settings:[{key:'autoSave',kind:'boolean',defaultValue:'true'}]},
      {...manifest,settings:[{key:'autoSave',kind:'boolean',defaultValue:true}]},
      {...manifest,settings:[{key:'autoSave',kind:'secret'}]},
      {...manifest,hooks:[],afterTurnEnabledSetting:undefined}
    ]) {
      await writeFile(manifestFile,JSON.stringify(invalid));
      await assert.rejects(()=>buildPlugin(entry,manifestFile,output));
    }
    await writeFile(manifestFile,JSON.stringify(manifest));
    await buildPlugin(entry,manifestFile,output);
    assert.equal(JSON.parse(await readFile(output,'utf8')).manifest.apiVersion,8);
  } finally { await rm(dir,{recursive:true}); }
});

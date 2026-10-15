import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile, mkdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { buildPlugin } from '../scripts/build-plugin.mjs';

test('API22 file import requires its service declaration and workspace permission', async () => {
  const dir=await mkdtemp(join(tmpdir(),'solvit-import-page-'));
  try {
    const entry=join(dir,'entry.ts'),file=join(dir,'manifest.json'),output=join(dir,'output.json');
    await writeFile(entry,'export default {tools:{}};');
    const manifest={id:'test.import.page',name:'Import',description:'Test',version:'1.0.0',apiVersion:22,
      permissions:['workspace'],nativeServices:['workspace.files'],dependencies:[],tools:[]};
    for(const invalid of [{...manifest,apiVersion:21},{...manifest,permissions:[]},{...manifest,nativeServices:['workspace.unknown']}]) {
      await writeFile(file,JSON.stringify(invalid));await assert.rejects(()=>buildPlugin(entry,file,output));
    }
    await writeFile(file,JSON.stringify(manifest));await buildPlugin(entry,file,output);
    assert.deepEqual(JSON.parse(await readFile(output,'utf8')).manifest,manifest);
  } finally {await rm(dir,{recursive:true})}
});

test('API19 Git pages bundle with explicit workspace/Git permissions and reject incompatible manifests', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-git-page-'));
  try {
    const entry = join(dir, 'entry.ts'), file = join(dir, 'manifest.json'), output = join(dir, 'output.json');
    await writeFile(entry, "export default {tools:{},pages:{main:async(input,ctx)=>{const status=await ctx.gitPage.query('status',{offset:0,limit:10});return {title:'Git',nodes:[{id:'state',kind:'text',text:status.branch}]};}}};");
    const manifest = {id:'test.git.page',name:'Git',description:'Test',version:'1.0.0',apiVersion:19,
      permissions:['workspace','git'],nativeServices:['workspace.git'],dependencies:[],tools:[],pages:[{id:'main',title:'Git'}]};
    for (const invalid of [{...manifest,apiVersion:18},{...manifest,permissions:['workspace']},
      {...manifest,permissions:['git']},{...manifest,nativeServices:['workspace.unknown']}]) {
      await writeFile(file,JSON.stringify(invalid));
      await assert.rejects(()=>buildPlugin(entry,file,output));
    }
    await writeFile(file,JSON.stringify(manifest)); await buildPlugin(entry,file,output);
    const sandbox=vm.createContext({}); vm.runInContext(JSON.parse(await readFile(output,'utf8')).source,sandbox);
    let reads=0;
    const view=await sandbox.solvitPlugin.pages.main({locale:'en',event:null},{gitPage:{query:async(operation,args)=>{
      assert.equal(operation,'status');assert.equal(args.offset,0);assert.equal(args.limit,10);reads++;return {branch:'main'};
    }}});
    assert.equal(reads,1);assert.equal(view.nodes[0].text,'main');
  } finally { await rm(dir,{recursive:true}); }
});

test('notes example supplies localized native pages and persists through plugin callbacks', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-notes-'));
  try {
    const output = join(dir,'notes.solvit.json');
    await buildPlugin(resolve('../plugin-examples/notes/index.ts'),resolve('../plugin-examples/notes/manifest.json'),output);
    const pkg = JSON.parse(await readFile(output,'utf8'));
    const sandbox = vm.createContext({}); vm.runInContext(pkg.source,sandbox);
    const storage = new Map();
    const context = {storage:{get:async key=>storage.get(key)??null,set:async(key,value)=>{storage.set(key,value);return null}}};
    const main=sandbox.solvitPlugin.pages.main;
    const initial=await main({locale:'en',event:null},context);
    assert.equal(initial.title,'Notes'); assert.equal(initial.nodes[0].value,'');
    const edited=await main({locale:'en',event:{nodeId:'save',action:'save',fields:{note:'a persistent note'}}},context);
    assert.equal(edited.nodes[0].value,'a persistent note');
    const reopened=await main({locale:'ja',event:null},context);
    assert.equal(reopened.title,'メモ'); assert.equal(reopened.nodes[0].value,'a persistent note');
    assert.ok(reopened.footer.find(node=>node.id==='delete').confirm);
    await main({locale:'zh',event:{nodeId:'delete',action:'delete',fields:{}}},context);
    assert.equal(storage.get('note'),'');
  } finally { await rm(dir,{recursive:true}); }
});

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

test('API30 local processes require Linux service and all three explicit permissions',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'solvit-stdio-'));
 try{
  const entry=join(dir,'entry.ts'),file=join(dir,'manifest.json'),output=join(dir,'output.json');
  await writeFile(entry,'export default {tools:{}};');
  const manifest={id:'test.stdio',name:'MCP',description:'Test',version:'1.0.0',apiVersion:30,permissions:['workspace','shell','network'],nativeServices:['environment.linux','process.stdio'],dependencies:[],tools:[]};
  for(const invalid of [{...manifest,apiVersion:29},{...manifest,nativeServices:['process.stdio']},{...manifest,permissions:['network','shell']}]){
   await writeFile(file,JSON.stringify(invalid));await assert.rejects(()=>buildPlugin(entry,file,output));
  }
  await writeFile(file,JSON.stringify(manifest));await buildPlugin(entry,file,output);
 }finally{await rm(dir,{recursive:true})}
});

test('API31 resource publication and per-tool workspace visibility require explicit compatible declarations', async()=>{
 const dir=await mkdtemp(join(tmpdir(),'solvit-resource-build-'));
 try{
  const entry=join(dir,'entry.ts'),file=join(dir,'manifest.json'),output=join(dir,'output.json');
  await writeFile(entry,'export default {tools:{list:async()=>({ok:true})}}');
  const manifest={id:'test.resources',name:'Resources',description:'Test',version:'1.0.0',apiVersion:31,
   permissions:['workspace'],nativeServices:['workspace.resources'],dependencies:[],tools:[{name:'list',description:'List',requiresWorkspace:false,inputSchema:{type:'object'}}]};
  for(const invalid of [{...manifest,apiVersion:30},{...manifest,permissions:[]},{...manifest,tools:[{...manifest.tools[0],requiresWorkspace:'false'}]}]){
   await writeFile(file,JSON.stringify(invalid));await assert.rejects(()=>buildPlugin(entry,file,output));
  }
  await writeFile(file,JSON.stringify(manifest));await buildPlugin(entry,file,output);
  assert.deepEqual(JSON.parse(await readFile(output,'utf8')).manifest,manifest);
 }finally{await rm(dir,{recursive:true})}
});

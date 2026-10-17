import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createMarketServer } from './server.mjs';

test('catalog downloads match their declared integrity and expose no write API', async () => {
  const server = createMarketServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const health = await (await fetch(base + '/health')).json();
    assert.equal(health.status, 'ok');
    const legacy = await (await fetch(base + '/api/v1/plugins')).json();
    assert.ok(legacy.plugins.every(plugin => plugin.apiVersion === 1));
    assert.equal(legacy.plugins.find(plugin => plugin.id === 'dev.solvit.clock').version, '1.0.0');
    const catalog = await (await fetch(base + '/api/v1/plugins?hostApi=2')).json();
    assert.ok(catalog.plugins.length > 0);
    assert.ok(catalog.plugins.every(plugin => plugin.apiVersion <= 2));
    const latest = await (await fetch(base + '/api/v1/plugins?hostApi=3')).json();
    assert.ok(latest.plugins.some(plugin => plugin.id === 'dev.solvit.skills' && plugin.apiVersion === 3));
    assert.equal(latest.plugins.find(plugin => plugin.id === 'dev.solvit.clock').version, '1.2.0');
    assert.equal(new Set(latest.plugins.map(plugin => plugin.id)).size, latest.plugins.length);
    assert.equal((await fetch(base + legacy.plugins.find(plugin => plugin.id === 'dev.solvit.clock').downloadPath)).status, 200);
    const api4 = await (await fetch(base + '/api/v1/plugins?hostApi=4')).json();
    assert.ok(api4.plugins.some(plugin => plugin.id === 'dev.solvit.workspace' && plugin.apiVersion === 4));
    assert.ok(api4.plugins.some(plugin => plugin.id === 'dev.solvit.authoring' && plugin.apiVersion === 4));
    assert.ok(latest.plugins.every(plugin => plugin.apiVersion < 4));
    const api5 = await (await fetch(base + '/api/v1/plugins?hostApi=5')).json();
    assert.ok(api5.plugins.some(plugin => plugin.id === 'dev.solvit.terminal' && plugin.apiVersion === 5));
    const api6 = await (await fetch(base + '/api/v1/plugins?hostApi=6')).json();
    assert.ok(api6.plugins.some(plugin => plugin.id === 'dev.solvit.terminal' && plugin.apiVersion === 5));
    assert.ok(api6.plugins.every(plugin => plugin.apiVersion <= 6));
    const api8 = await (await fetch(base + '/api/v1/plugins?hostApi=8')).json();
    assert.ok(api8.plugins.every(plugin => plugin.apiVersion <= 8));
    assert.equal(api8.plugins.find(plugin => plugin.id === 'dev.solvit.memory').version, '1.2.0');
    const api9 = await (await fetch(base + '/api/v1/plugins?hostApi=9')).json();
    const memoryPage = api9.plugins.find(plugin => plugin.id === 'dev.solvit.memory');
    assert.equal(memoryPage.version, '1.3.1');
    assert.equal(memoryPage.apiVersion, 9);
    assert.deepEqual(memoryPage.pages, [{id:'main',title:'记忆'},{id:'tasks',title:'自动记忆任务'}]);
    const api10 = await (await fetch(base + '/api/v1/plugins?hostApi=10')).json();
    const htmlMemory = api10.plugins.find(plugin => plugin.id === 'dev.solvit.memory');
    assert.equal(htmlMemory.apiVersion, 10); assert.equal(htmlMemory.version, '1.4.2');
    assert.ok(htmlMemory.pages.every(page => page.entry === 'ui/index.html'));
    const htmlPackage = await (await fetch(base + htmlMemory.downloadPath)).json();
    assert.ok(htmlPackage.resources['ui/index.html'].includes('id="graph"'));
    assert.ok(htmlPackage.resources['ui/main.js'].length > 100_000);
    const oldMcp = api9.plugins.find(plugin => plugin.id === 'dev.solvit.mcp');
    assert.equal(oldMcp.version, '1.0.0'); assert.equal(oldMcp.apiVersion, 2);
    const multiMcp = api10.plugins.find(plugin => plugin.id === 'dev.solvit.mcp');
    assert.equal(multiMcp.version, '1.1.1'); assert.equal(multiMcp.apiVersion, 10);
    assert.equal(multiMcp.pages[0].entry, 'ui/index.html');
    const mcpPackage = await (await fetch(base + multiMcp.downloadPath)).json();
    assert.ok(mcpPackage.resources['ui/main.js']);
    assert.ok(mcpPackage.manifest.settings.every(setting => setting.pageManaged));
    const previousSkills = api10.plugins.find(plugin => plugin.id === 'dev.solvit.skills');
    assert.equal(previousSkills.version, '1.0.2'); assert.equal(previousSkills.apiVersion, 3);
    const api11 = await (await fetch(base + '/api/v1/plugins?hostApi=11')).json();
    const htmlSkills = api11.plugins.find(plugin => plugin.id === 'dev.solvit.skills');
    assert.equal(htmlSkills.version, '1.1.1'); assert.equal(htmlSkills.apiVersion, 11);
    const api12 = await (await fetch(base + '/api/v1/plugins?hostApi=12')).json();
    const htmlWorkspace = api12.plugins.find(plugin => plugin.id === 'dev.solvit.workspace');
    assert.equal(htmlWorkspace.version, '1.1.0'); assert.equal(htmlWorkspace.apiVersion, 12);
    const api16 = await (await fetch(base + '/api/v1/plugins?hostApi=16')).json();
    const projectRules = api16.plugins.find(plugin => plugin.id === 'dev.solvit.workspace');
    assert.equal(projectRules.version, '1.2.0'); assert.equal(projectRules.apiVersion, 16);
    assert.deepEqual(projectRules.hooks, ['beforeModel']);
    const api17 = await (await fetch(base + '/api/v1/plugins?hostApi=17')).json();
    const fileReview = api17.plugins.find(plugin => plugin.id === 'dev.solvit.workspace');
    assert.equal(fileReview.version, '1.3.0'); assert.equal(fileReview.apiVersion, 17);
    assert.equal(fileReview.pages.find(page => page.id === 'changes').entry, 'review/index.html');
    const originalReview=Buffer.from(await (await fetch(base+fileReview.downloadPath)).arrayBuffer());
    assert.equal(createHash('sha256').update(originalReview).digest('hex'),'95531ef7faaf38df4f1d56954505c848243bdcb2e4a773ac588b74e471ff037b');
    const api18=await (await fetch(base+'/api/v1/plugins?hostApi=18')).json();
    const editor=api18.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(editor.version,'1.4.0');assert.equal(editor.apiVersion,18);
    assert.deepEqual(api18.plugins.filter(plugin=>plugin.id!=='dev.solvit.workspace'),api17.plugins.filter(plugin=>plugin.id!=='dev.solvit.workspace'));
    const editorPackage=await (await fetch(base+editor.downloadPath)).json();
    assert.ok(editorPackage.resources['ui/main.js'].includes('cm-editor'));
    assert.ok(editorPackage.resources['ui/editor-NOTICE.txt'].includes('Permission is hereby granted'));
    const api22=await(await fetch(base+'/api/v1/plugins?hostApi=22')).json();
    const importer=api22.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(importer.version,'1.5.0');assert.equal(importer.apiVersion,22);
    assert.deepEqual(importer.nativeServices,['workspace.files']);
    const api23=await(await fetch(base+'/api/v1/plugins?hostApi=23')).json();
    const pdfWorkspace=api23.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(pdfWorkspace.version,'1.6.0');assert.equal(pdfWorkspace.apiVersion,23);
    const api24=await(await fetch(base+'/api/v1/plugins?hostApi=24')).json();
    const chatWorkspace=api24.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(chatWorkspace.version,'1.7.0');assert.equal(chatWorkspace.apiVersion,24);
    assert.deepEqual(chatWorkspace.nativeServices,['workspace.files']);
    const api25=await(await fetch(base+'/api/v1/plugins?hostApi=25')).json();
    const reviewedWorkspace=api25.plugins.find(p=>p.id==='dev.solvit.workspace');
    assert.equal(reviewedWorkspace.version,'1.8.0');assert.equal(reviewedWorkspace.apiVersion,25);
    const api26=await(await fetch(base+'/api/v1/plugins?hostApi=26')).json();
    const transferWorkspace=api26.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(transferWorkspace.version,'1.9.0');assert.equal(transferWorkspace.apiVersion,26);
    const api27=await(await fetch(base+'/api/v1/plugins?hostApi=27')).json();
    const recoveryWorkspace=api27.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(recoveryWorkspace.version,'1.10.0');assert.equal(recoveryWorkspace.apiVersion,27);
    const api28=await(await fetch(base+'/api/v1/plugins?hostApi=28')).json();
    const managedBackups=api28.plugins.find(plugin=>plugin.id==='dev.solvit.workspace');
    assert.equal(managedBackups.version,'1.11.0');assert.equal(managedBackups.apiVersion,28);
    assert.equal(api28.plugins.find(plugin=>plugin.id==='dev.solvit.git').version,'1.2.7');
    const api29=await(await fetch(base+'/api/v1/plugins?hostApi=29')).json();
    const recoveryGit=api29.plugins.find(plugin=>plugin.id==='dev.solvit.git');
    assert.equal(recoveryGit.version,'1.3.1');assert.equal(recoveryGit.apiVersion,29);
    const previousGit130=Buffer.from(await(await fetch(base+'/api/v1/packages/dev.solvit.git/1.3.0')).arrayBuffer());
    assert.equal(createHash('sha256').update(previousGit130).digest('hex'),'0e2a8f1d09cf800530f4d0fd6000a0174a491c8d59a8e5843c221decbc795235');
    const previousGit127=Buffer.from(await(await fetch(base+'/api/v1/packages/dev.solvit.git/1.2.7')).arrayBuffer());
    assert.equal(createHash('sha256').update(previousGit127).digest('hex'),'96811c76f4b14800829de63ab1cc848d82892915472dcee0a3ea4ad29348b28c');
    for(const [id,version,sha] of [
      ['workspace','1.10.0','e275e637b3cdbe3e56d800d9abbefc221169b7f1e07faed8f29c5d6a611cbfd9'],
      ['workspace','1.9.0','c4bd5bfa19dd9bca67d6913c02a48bc2624858d9be5eb32f7863c6a0273d5f5e'],
      ['git','1.2.6','272b679dad15a9d595fe4299f931427cec4825a40442aebb93251dff9afa7bb8'],
      ['git','1.2.5','ee8cab14b1992ee28651c0ce510d9e046d66a396bc559d2d7293a2aadeb277b4']]){
      const original=Buffer.from(await(await fetch(base+`/api/v1/packages/dev.solvit.${id}/${version}`)).arrayBuffer());
      assert.equal(createHash('sha256').update(original).digest('hex'),sha);
    }
    for(const [id,version,sha] of [
      ['workspace','1.8.0','e5ad26b643d323bd3b622094121316b94eaaf17400d62397c91243c5e7a3fe5d'],
      ['git','1.2.4','c7c771112dbd15eea56144802306d4f9432e9061ea7047e2ceb765e5141e3da0']]) {
      const original=Buffer.from(await(await fetch(base+`/api/v1/packages/dev.solvit.${id}/${version}`)).arrayBuffer());
      assert.deepEqual(original,await readFile(new URL(`./packages/legacy/dev.solvit.${id}-${version}.solvit.json`,import.meta.url)));
      assert.equal(createHash('sha256').update(original).digest('hex'),sha);
    }
    for(const [id,version,sha] of [['workspace','1.7.0','d212d6741bde9a5db30a9d307c506389343d9c705f66011d4c0a50e54c041360'],['git','1.2.3','f35a229cf891a942e6e099a4fd13cacc7367c09c0ce2f77e0fa1c293249a9e65']]){
      const original=Buffer.from(await(await fetch(base+`/api/v1/packages/dev.solvit.${id}/${version}`)).arrayBuffer());
      assert.equal(createHash('sha256').update(original).digest('hex'),sha);
    }
    for(const [id,version,sha] of [['workspace','1.6.0','7e365776b2df302b79d338980e5ce990f233d7f3a7762399a89c8fa3a50283b6'],['git','1.2.2','1d7aa4b3556104907dac7e29d7ea4fb5e35a7f6565d3a6bf668771e643b8130b']]){
      const original=Buffer.from(await(await fetch(base+`/api/v1/packages/dev.solvit.${id}/${version}`)).arrayBuffer());
      assert.deepEqual(original,await readFile(new URL(`./packages/legacy/dev.solvit.${id}-${version}.solvit.json`,import.meta.url)));
      assert.equal(createHash('sha256').update(original).digest('hex'),sha);
    }
    assert.deepEqual(pdfWorkspace.nativeServices,['workspace.files']);
    const originalImports=Buffer.from(await(await fetch(base+'/api/v1/packages/dev.solvit.workspace/1.5.0')).arrayBuffer());
    assert.deepEqual(originalImports,await readFile(new URL('./packages/legacy/dev.solvit.workspace-1.5.0.solvit.json',import.meta.url)));
    assert.equal(createHash('sha256').update(originalImports).digest('hex'),'399e6a23148c4043903788adeb2eb7203504710c3fe95b6af9a39d89b38bc72a');
    const previousGit=Buffer.from(await(await fetch(base+'/api/v1/packages/dev.solvit.git/1.2.1')).arrayBuffer());
    assert.deepEqual(previousGit,await readFile(new URL('./packages/legacy/dev.solvit.git-1.2.1.solvit.json',import.meta.url)));
    assert.equal(createHash('sha256').update(previousGit).digest('hex'),'77454f4db363a764fafd507dc00a695dedb4fdc09f8f420d262d3b4d653d83f9');
    const api21=await(await fetch(base+'/api/v1/plugins?hostApi=21')).json();
    assert.equal(api21.plugins.find(plugin=>plugin.id==='dev.solvit.workspace').version,'1.4.0');
    const preserved=Buffer.from(await(await fetch(base+'/api/v1/packages/dev.solvit.workspace/1.4.0')).arrayBuffer());
    assert.deepEqual(preserved,await readFile(new URL('./packages/legacy/dev.solvit.workspace-1.4.0.solvit.json',import.meta.url)));
    const rulesPrevious = Buffer.from(await (await fetch(base + '/api/v1/packages/dev.solvit.workspace/1.2.0')).arrayBuffer());
    assert.deepEqual(rulesPrevious, await readFile(new URL('./packages/legacy/dev.solvit.workspace-1.2.0.solvit.json', import.meta.url)));
    const workspacePrevious = Buffer.from(await (await fetch(base + '/api/v1/packages/dev.solvit.workspace/1.1.0')).arrayBuffer());
    assert.deepEqual(workspacePrevious, await readFile(new URL('./packages/legacy/dev.solvit.workspace-1.1.0.solvit.json', import.meta.url)));
    const api13 = await (await fetch(base + '/api/v1/plugins?hostApi=13')).json();
    const htmlTerminal = api13.plugins.find(plugin => plugin.id === 'dev.solvit.terminal');
    assert.equal(htmlTerminal.version, '1.1.3'); assert.equal(htmlTerminal.apiVersion, 13);
    assert.equal(htmlTerminal.pages[0].entry, 'ui/index.html');
    const api14 = await (await fetch(base + '/api/v1/plugins?hostApi=14')).json();
    const agent = api14.plugins.find(plugin => plugin.id === 'example.agent');
    assert.equal(agent.apiVersion, 14);
    assert.ok(!api13.plugins.some(plugin => plugin.id === 'example.agent'));
    const agentPackage = await (await fetch(base + agent.downloadPath)).json();
    assert.equal(agentPackage.manifest.agentEnabledSetting, 'selected');
    assert.deepEqual(agentPackage.manifest.permissions, ['agent']);
    assert.equal(api12.plugins.find(plugin => plugin.id === 'dev.solvit.terminal').version, '1.0.0');
    for(const [id,previous] of [['memory','1.4.0'],['terminal','1.1.0'],['terminal','1.1.1'],['terminal','1.1.2']]) {
      const original = Buffer.from(await (await fetch(base + `/api/v1/packages/dev.solvit.${id}/${previous}`)).arrayBuffer());
      assert.deepEqual(original, await readFile(new URL(`./packages/legacy/dev.solvit.${id}-${previous}.solvit.json`, import.meta.url)));
    }
    const originalTerminalBytes = Buffer.from(await (await fetch(base + '/api/v1/packages/dev.solvit.terminal/1.0.0')).arrayBuffer());
    assert.deepEqual(originalTerminalBytes, await readFile(new URL('./packages/legacy/dev.solvit.terminal-1.0.0.solvit.json', import.meta.url)));
    assert.equal(htmlWorkspace.pages[0].entry, 'ui/index.html');
    assert.equal(api11.plugins.find(plugin => plugin.id === 'dev.solvit.workspace').version, '1.0.0');
    assert.equal(htmlSkills.pages[0].entry, 'ui/index.html');
    const oldSkillsBytes = Buffer.from(await (await fetch(base + previousSkills.downloadPath)).arrayBuffer());
    assert.deepEqual(oldSkillsBytes, await readFile(new URL('./packages/legacy/dev.solvit.skills-1.0.2.solvit.json', import.meta.url)));
    const notesPage = api9.plugins.find(plugin => plugin.id === 'example.notes');
    assert.equal(notesPage.apiVersion, 9);
    assert.deepEqual(notesPage.pages, [{id:'main',title:'便签'}]);
    assert.ok(!api8.plugins.some(plugin => plugin.id === 'example.notes'));
    const api7 = await (await fetch(base + '/api/v1/plugins?hostApi=7')).json();
    assert.ok(api7.plugins.every(plugin => plugin.apiVersion <= 7));
    assert.ok(api7.plugins.length >= api6.plugins.length);
    assert.equal(api7.plugins.find(plugin => plugin.id === 'dev.solvit.memory').version, '1.1.0');
    assert.equal(api6.plugins.find(plugin => plugin.id === 'dev.solvit.memory').version, '1.0.0');
    assert.equal(api6.plugins.find(plugin => plugin.id === 'dev.solvit.web-search').version, '1.2.2');
    const previousSearch121 = Buffer.from(await (await fetch(base + '/api/v1/packages/dev.solvit.web-search/1.2.1')).arrayBuffer());
    assert.deepEqual(previousSearch121, await readFile(new URL('./packages/legacy/dev.solvit.web-search-1.2.1.solvit.json', import.meta.url)));
    const previousSearch = Buffer.from(await (await fetch(base + '/api/v1/packages/dev.solvit.web-search/1.2.0')).arrayBuffer());
    assert.deepEqual(previousSearch, await readFile(new URL('./packages/legacy/dev.solvit.web-search-1.2.0.solvit.json', import.meta.url)));
    assert.equal(api5.plugins.find(plugin => plugin.id === 'dev.solvit.web-search').version, '1.1.0');
    assert.ok(api4.plugins.every(plugin => plugin.apiVersion <= 4));
    for (const plugin of [...api5.plugins, ...api7.plugins, ...api8.plugins, ...api9.plugins, ...api10.plugins, ...api11.plugins, ...api12.plugins, ...api13.plugins, ...api16.plugins]) {
      const response = await fetch(base + plugin.downloadPath);
      assert.equal(response.status, 200);
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(bytes.length, plugin.bytes);
      assert.equal(createHash('sha256').update(bytes).digest('hex'), plugin.sha256);
      assert.equal(JSON.parse(bytes).manifest.id, plugin.id);
    }
    assert.equal((await fetch(base + '/api/v1/plugins', { method: 'POST', body: '{}' })).status, 405);
    assert.equal((await fetch(base + '/api/v1/packages/unknown.plugin/1.0.0')).status, 404);
    assert.equal((await fetch(base + '/api/v1/packages/../../server.mjs')).status, 404);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('MCP demo negotiates read-only tools and rejects unknown calls and origins', async () => {
  const server = createMarketServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const endpoint = `http://127.0.0.1:${server.address().port}/mcp-demo`;
  let id = 0;
  const rpc = async (method, params) => (await fetch(endpoint, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({jsonrpc:'2.0',id:++id,method,params}) })).json();
  try {
    assert.equal((await rpc('initialize',{protocolVersion:'2025-11-25'})).result.protocolVersion,'2025-11-25');
    assert.equal((await rpc('tools/list',{})).result.tools.length,2);
    assert.equal((await rpc('tools/call',{name:'echo',arguments:{text:'你好'}})).result.content[0].text,'你好');
    assert.equal((await rpc('tools/call',{name:'shell',arguments:{}})).error.code,-32602);
    assert.equal((await fetch(endpoint,{method:'POST',headers:{Origin:'https://untrusted.example'}})).status,403);
    assert.equal((await fetch(endpoint)).status,405);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('API19 keeps the exact released Git bytes while API20 selects remote Git',async()=>{
 const server=createMarketServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${server.address().port}`;
 try{
  const old=(await(await fetch(base+'/api/v1/plugins?hostApi=19')).json()).plugins.find(p=>p.id==='dev.solvit.git');
  assert.equal(old.version,'1.1.1');assert.equal(old.apiVersion,19);
  const oldBytes=Buffer.from(await(await fetch(base+old.downloadPath)).arrayBuffer());
  assert.deepEqual(oldBytes,await readFile(new URL('./packages/legacy/dev.solvit.git-1.1.1.solvit.json',import.meta.url)));
  assert.equal(createHash('sha256').update(oldBytes).digest('hex'),'7babc02c3e0feab6bf717bcc8de306dbd9f4896d20443217ab807858454378dd');
  const next=(await(await fetch(base+'/api/v1/plugins?hostApi=20')).json()).plugins.find(p=>p.id==='dev.solvit.git');
  assert.equal(next.version,'1.2.7');assert.equal(next.apiVersion,20);
 }finally{await new Promise(resolve=>server.close(resolve))}
});

// Captured from the deployed API19 catalog before moving Git 1.1.1 to legacy.
test('API19 retains its published plugin order after Git advances to API20', async () => {
  const server = createMarketServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    const catalog = await (await fetch(base + '/api/v1/plugins?hostApi=19')).json();
    assert.deepEqual(catalog.plugins.map(plugin => plugin.id), ["dev.solvit.memory", "dev.solvit.workspace", "dev.solvit.web-search", "dev.solvit.clock", "dev.solvit.terminal", "dev.solvit.git", "dev.solvit.mcp", "dev.solvit.skills", "dev.solvit.authoring", "example.agent-policy", "example.agent", "example.notes"]);
    const git = catalog.plugins.find(plugin => plugin.id === 'dev.solvit.git');
    assert.equal(git.version, '1.1.1');
    assert.equal(git.sha256, '7babc02c3e0feab6bf717bcc8de306dbd9f4896d20443217ab807858454378dd');
  } finally { await new Promise(resolve => server.close(resolve)); }
});


test('API21 environment is offered only to compatible hosts and API20 remains intact', async()=>{
 const server=createMarketServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${server.address().port}`;
 try {
  const previous=await(await fetch(base+'/api/v1/plugins?hostApi=20')).json();
  assert.equal(previous.plugins.some(p=>p.id==='dev.solvit.environment'),false);
  const latest=await(await fetch(base+'/api/v1/plugins?hostApi=21')).json();
  const environment=latest.plugins.find(p=>p.id==='dev.solvit.environment');
  assert.equal(environment.apiVersion,21);
  assert.deepEqual(environment.nativeServices,['environment.linux']);
  assert.deepEqual(latest.plugins.filter(p=>p.id!=='dev.solvit.environment'),previous.plugins);
 }finally{await new Promise(resolve=>server.close(resolve))}
});

test('API30 offers local MCP while API10 through API29 retain immutable HTTPS MCP',async()=>{
 const server=createMarketServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${server.address().port}`;
 try{
  for(let api=10;api<=29;api++){
   const catalog=await(await fetch(base+`/api/v1/plugins?hostApi=${api}`)).json();
   const mcp=catalog.plugins.find(p=>p.id==='dev.solvit.mcp');assert.equal(mcp.version,'1.1.1');assert.equal(mcp.apiVersion,10);
   const bytes=Buffer.from(await(await fetch(base+mcp.downloadPath)).arrayBuffer());
   assert.deepEqual(bytes,await readFile(new URL('./packages/legacy/dev.solvit.mcp-1.1.1.solvit.json',import.meta.url)));
  }
  const catalog=await(await fetch(base+'/api/v1/plugins?hostApi=30')).json();const mcp=catalog.plugins.find(p=>p.id==='dev.solvit.mcp');
  assert.equal(mcp.version,'1.2.0');assert.equal(mcp.apiVersion,30);assert.deepEqual(mcp.nativeServices,['environment.linux','process.stdio']);
 }finally{await new Promise(resolve=>server.close(resolve))}
});

test('API31 Skills resources retain the exact read-only Skills package for API11 through API30',async()=>{
 const server=createMarketServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base=`http://127.0.0.1:${server.address().port}`;
 try{
  const historical=await readFile(new URL('./packages/legacy/dev.solvit.skills-1.1.1.solvit.json',import.meta.url));
  for(let api=11;api<=30;api++){
   const catalog=await(await fetch(base+`/api/v1/plugins?hostApi=${api}`)).json();
   const skills=catalog.plugins.find(p=>p.id==='dev.solvit.skills');assert.equal(skills.version,'1.1.1');
   assert.deepEqual(Buffer.from(await(await fetch(base+skills.downloadPath)).arrayBuffer()),historical);
  }
  const latest=await(await fetch(base+'/api/v1/plugins?hostApi=31')).json();
  const skills=latest.plugins.find(p=>p.id==='dev.solvit.skills');assert.equal(skills.version,'1.2.0');assert.equal(skills.apiVersion,31);
  assert.deepEqual(skills.nativeServices,['workspace.resources']);assert.deepEqual(skills.permissions,['storage','workspace']);
  const payload=Buffer.from(await(await fetch(base+skills.downloadPath)).arrayBuffer());
  assert.equal(createHash('sha256').update(payload).digest('hex'),skills.sha256);
  assert.deepEqual(payload,await readFile(new URL('./packages/dev.solvit.skills.solvit.json',import.meta.url)));
 }finally{await new Promise(resolve=>server.close(resolve))}
});

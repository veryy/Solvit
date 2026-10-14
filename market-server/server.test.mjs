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
    assert.equal(htmlMemory.apiVersion, 10); assert.equal(htmlMemory.version, '1.4.1');
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
  assert.equal(next.version,'1.2.0');assert.equal(next.apiVersion,20);
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

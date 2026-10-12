import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.mcp.solvit.json', 'utf8'));
function client(options = {}) {
  const context = vm.createContext({}); vm.runInContext(pkg.source, context, { timeout: 1000 });
  const requests = [];
  const ctx = {
    settings: { async get(key) { return key === 'url' ? 'https://example.com/mcp' : key === 'token' ? 'private-token' : null; } },
    http: { async request(request) {
      requests.push(request);
      if (request.method === 'DELETE') return { status: 405, headers: {}, body: '' };
      const rpc = JSON.parse(request.body);
      assert.equal(request.headers.Authorization, 'Bearer private-token');
      if (rpc.method === 'initialize') return { status: 200, headers: { 'mcp-session-id': 'session-id', 'content-type': 'application/json' }, body: JSON.stringify({ jsonrpc:'2.0', id:rpc.id, result:{ protocolVersion: options.version ?? '2025-06-18', capabilities:{tools:{}}, serverInfo:{name:'fixture',version:'1'} } }) };
      assert.equal(request.headers['Mcp-Session-Id'], 'session-id');
      assert.equal(request.headers['MCP-Protocol-Version'], '2025-06-18');
      if (!rpc.id) return { status: 202, body:'', headers:{} };
      const result = rpc.method === 'tools/list'
        ? (rpc.params.cursor ? { tools:[{name:'other',inputSchema:{type:'object'}}] } : { tools:[{name:'echo',description:'Echo',inputSchema:{type:'object'}}],nextCursor:'page-two' })
        : { content:[{type:'text',text:rpc.params.arguments.text}] };
      if (rpc.method === 'tools/call' && options.expired) return { status:404,body:'private-token',headers:{} };
      if (rpc.method === 'tools/call' && options.error) return { status:200,headers:{'content-type':'application/json'},body:JSON.stringify({jsonrpc:'2.0',id:rpc.id,error:{code:-32000,message:'private-token'}}) };
      const response = { jsonrpc:'2.0',id:options.badId ? 999 : rpc.id,result };
      return options.sse ? { status:200,headers:{'content-type':'text/event-stream'},body:': heartbeat\r\n\r\ndata: '+JSON.stringify({jsonrpc:'2.0',method:'notifications/progress',params:{}})+'\r\n\r\ndata: '+JSON.stringify(response)+'\r\n\r\n' }
        : {status:200,headers:{'content-type':'application/json'},body:JSON.stringify(response)};
    } },
  };
  return { requests, async run(tool, args={}) { return JSON.parse(JSON.stringify(await context.solvitPlugin.tools[tool](args,ctx))); }, async stop() { await context.solvitPlugin.deactivate(ctx); } };
}
test('negotiates sessions, pages tool discovery, executes calls and closes sessions', async () => {
  const plugin = client();
  const connected = await plugin.run('mcp_connect');
  assert.equal(connected.protocol,'2025-06-18'); assert.equal(connected.tools,2);
  const tools = await plugin.run('mcp_tools',{offset:1,limit:1}); assert.equal(tools.tools[0].name,'other');
  const result = await plugin.run('mcp_call',{name:'echo',arguments:{text:'你好'}});
  assert.equal(result.content[0].text,'你好');
  const cancel = JSON.parse(plugin.requests.find(request => request.body?.includes('tools/call')).cancelBody);
  assert.equal(cancel.method, 'notifications/cancelled'); assert.equal(cancel.params.requestId, 4);
  await plugin.stop(); assert.equal(plugin.requests.at(-1).method,'DELETE');
  assert.equal(JSON.stringify(connected).includes('private-token'),false);
});
test('reads finite SSE responses and ignores progress notifications', async () => {
  const plugin = client({sse:true});
  assert.equal((await plugin.run('mcp_call',{name:'echo',arguments:{text:'SSE'}})).content[0].text,'SSE');
});
test('never executes unknown tools or retries calls after expired sessions', async () => {
  const plugin = client({expired:true});
  await assert.rejects(()=>plugin.run('mcp_call',{name:'missing',arguments:{}}),/not discovered/);
  assert.equal(plugin.requests.filter(r=>r.body?.includes('tools/call')).length,0);
  await assert.rejects(()=>plugin.run('mcp_call',{name:'echo',arguments:{text:'x'}}),/expired/);
  assert.equal(plugin.requests.filter(r=>r.body?.includes('tools/call')).length,1);
});
test('rejects mismatched response IDs and unsupported protocol versions', async () => {
  await assert.rejects(()=>client({badId:true}).run('mcp_connect'),/request ID/);
  await assert.rejects(()=>client({version:'future'}).run('mcp_connect'),/unsupported/);
});
test('server diagnostic errors do not expose configured bearer token', async () => {
  await assert.rejects(()=>client({error:true}).run('mcp_call',{name:'echo',arguments:{}}),error=>error.message==='MCP JSON-RPC error -32000');
});

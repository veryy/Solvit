import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const pkg=JSON.parse(await readFile('../market-server/packages/dev.solvit.mcp.solvit.json','utf8'));
const flatten=view=>{const out=[];const walk=n=>{out.push(n);(n.children??[]).forEach(walk)};[...view.nodes,...view.footer??[]].forEach(walk);return out};
function fixture(){
  const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
  const profiles=[{name:'资料',url:'https://docs.example/mcp',token:'private-docs',headers:{},disabled:false},
    {name:'code',url:'https://code.example/mcp',token:'private-code',headers:{},disabled:false}];
  const values=new Map([['connections',JSON.stringify(profiles)]]),requests=[];let expireDocs=false;
  const ctx={settings:{get:async key=>values.get(key)??null,compareAndSet:async(key,expected,value)=>{
    if((values.get(key)??null)!==expected)return false;values.set(key,value);return true;
  }},http:{request:async request=>{
    requests.push(request);const name=request.url.includes('docs.')?'资料':'code',token=name==='资料'?'private-docs':'private-code';
    assert.equal(request.headers.Authorization,'Bearer '+token);
    if(request.method==='DELETE')return {status:200,headers:{},body:''};
    const rpc=JSON.parse(request.body);
    const sessionId=name==='资料'?'session-docs':'session-code';
    if(rpc.method==='initialize')return {status:200,headers:{'mcp-session-id':sessionId},body:JSON.stringify({jsonrpc:'2.0',id:rpc.id,result:{protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name,version:'1'}}})};
    assert.equal(request.headers['Mcp-Session-Id'],sessionId);
    if(!rpc.id)return {status:202,headers:{},body:''};
    if(rpc.method==='tools/call'&&name==='资料'&&expireDocs)return {status:404,headers:{},body:'private-docs'};
    return {status:200,headers:{},body:JSON.stringify({jsonrpc:'2.0',id:rpc.id,result:rpc.method==='tools/list'?{tools:[{name:'echo',inputSchema:{type:'object'}}]}:{content:[{type:'text',text:name+':'+rpc.params.arguments.text}]}})};
  }}};
  return {ctx,profiles,values,requests,expire(){expireDocs=true},run:async(name,args={})=>JSON.parse(JSON.stringify(await sandbox.solvitPlugin.tools[name](args,ctx))),
    page:async(action,fields={},nodeId=action)=>JSON.parse(JSON.stringify(await sandbox.solvitPlugin.pages.main({locale:'zh',event:action?{action,fields,nodeId}:null},ctx))),
    stop:()=>sandbox.solvitPlugin.deactivate(ctx)};
}
test('same remote tool name routes to two independent servers, and one expired session leaves the other usable',async()=>{
  const f=fixture();const listed=await f.run('mcp_servers');
  assert.deepEqual(listed.servers.map(s=>s.name),['资料','code']);assert.ok(!JSON.stringify(listed).includes('private-'));
  await assert.rejects(()=>f.run('mcp_connect'),/Select/);assert.equal(f.requests.length,0);
  assert.equal((await f.run('mcp_call',{server:'资料',name:'echo',arguments:{text:'A'}})).content[0].text,'资料:A');
  assert.equal((await f.run('mcp_call',{server:'code',name:'echo',arguments:{text:'B'}})).content[0].text,'code:B');
  assert.equal((await f.run('mcp_tools',{server:'code'})).server,'code');
  f.expire();await assert.rejects(()=>f.run('mcp_call',{server:'资料',name:'echo',arguments:{text:'C'}}),/expired/);
  assert.equal((await f.run('mcp_call',{server:'code',name:'echo',arguments:{text:'D'}})).content[0].text,'code:D');
  assert.equal(f.requests.filter(r=>r.body&&JSON.parse(r.body).method==='initialize'&&r.url.includes('code.')).length,1);
  await f.stop();assert.equal(f.requests.at(-1).method,'DELETE');assert.equal(f.requests.at(-1).url,'https://code.example/mcp');
});
test('disabling or changing one profile invalidates its session without exposing credentials or retrying calls',async()=>{
  const f=fixture();await f.run('mcp_connect',{server:'code'});
  f.profiles[1].disabled=true;f.values.set('connections',JSON.stringify(f.profiles));
  const status=await f.run('mcp_servers');assert.equal(status.servers[1].status,'disabled');assert.equal(f.requests.at(-1).method,'DELETE');
  const count=f.requests.length;await assert.rejects(()=>f.run('mcp_call',{server:'code',name:'echo',arguments:{}}),/disabled/);assert.equal(f.requests.length,count);
  assert.ok(!JSON.stringify(status).includes('private-code'));
});
test('HTML management imports explicit multiple servers atomically, rejects stdio and preserves concurrent changes',async()=>{
  const f=fixture();await f.page();await f.page('import');
  const invalid=await f.page('import_save',{config:JSON.stringify({mcpServers:{bad:{command:'npx',args:[]}}})});
  assert.ok(flatten(invalid).some(n=>n.id==='message'));assert.equal(JSON.parse(f.values.get('connections')).length,2);
  await f.page('back');await f.page('edit',{},'edit_0');
  const current=f.values.get('connections');f.values.set('connections',JSON.stringify([f.profiles[1]]));
  const conflict=await f.page('save',{name:'new',url:'https://new.example/mcp',token:'',headers:''});
  assert.ok(flatten(conflict).some(n=>n.text?.includes('配置已变化')));assert.equal(JSON.parse(f.values.get('connections')).length,1);
  await f.page('back');await f.page('import');
  const data={mcpServers:{first:{type:'streamable_http',url:'https://first.example/mcp',headers:{Authorization:'Bearer secret'},disabled:true},second:{type:'http',url:'https://second.example/mcp'}}};
  await f.page('import_save',{config:JSON.stringify(data)});
  const stored=JSON.parse(f.values.get('connections'));assert.equal(stored.length,2);assert.equal(stored[0].disabled,true);assert.equal(stored[0].headers.Authorization,'Bearer secret');
  assert.ok(!JSON.stringify(await f.page()).includes('Bearer secret'));
  assert.ok(pkg.resources['ui/main.js']);assert.ok(pkg.manifest.settings.every(field=>field.pageManaged));
});

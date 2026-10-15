import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const pkg=JSON.parse(await readFile('../market-server/packages/dev.solvit.mcp.solvit.json','utf8'));
const flatten=view=>{const out=[];const walk=n=>{out.push(n);(n.children??[]).forEach(walk)};[...view.nodes,...view.footer??[]].forEach(walk);return out};
function fixture(){
 const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
 const profile={name:'local',type:'stdio',command:'python3',args:['server.py'],env:{TOKEN:'keep-private'},cwd:'',disabled:false};
 const settings=new Map([['connections',JSON.stringify([profile])]]),processes=new Map(),frames=[],closed=[];
 let scope='chat-a',generation=0,fail=false,wrongId=false,ping=false;
 const ctx={settings:{get:async k=>settings.get(k)??null,compareAndSet:async(k,expected,next)=>{
  if(settings.get(k)!==expected)return false;settings.set(k,next);processes.clear();return true;
 }},http:{request:async()=>{throw new Error('Local transport must not call HTTP')}},stdio:{
  scope:async()=>scope,review:async name=>{if(!scope)throw new Error('Unbound');return {reviewId:'8bc86498-7eb0-4aee-b642-d9a3531eb019',command:'python3',argumentCount:1,environmentCount:1}},
  open:async name=>{assert.equal(name,'local');if(!scope)throw new Error('Unbound');const previous=[...processes.entries()].find(([,p])=>p.scope===scope);if(previous)return previous[0];const id=`process-${++generation}`;processes.set(id,{scope,queue:[]});return id},
  send:async(name,id,frame)=>{assert.equal(name,'local');const p=processes.get(id);assert.ok(p);assert.equal(p.scope,scope);frames.push(JSON.parse(JSON.stringify(frame)));
   if(frame.method==='notifications/initialized'||!frame.method)return null;
   if(fail&&frame.method==='tools/call')throw new Error('disconnected');
   const result=frame.method==='initialize'?{protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name:'fixture'}}:
    frame.method==='tools/list'?{tools:[{name:'write',inputSchema:{type:'object'}}]}:{content:[{type:'text',text:scope+':'+frame.params.arguments.text}]};
   if(ping)p.queue.push({jsonrpc:'2.0',method:'ping',id:'server-ping'});
   p.queue.push({jsonrpc:'2.0',id:wrongId?'wrong':frame.id,result});return null;
  },
  receive:async(name,id)=>{const p=processes.get(id);assert.equal(p.scope,scope);assert.ok(p.queue.length);return p.queue.shift()},
  close:async(name,id)=>{const p=processes.get(id);if(p)assert.equal(p.scope,scope);closed.push(id);processes.delete(id);return null},
 }};
 return {settings,profile,frames,closed,processes,scope:value=>scope=value,fail:()=>fail=true,wrongId:()=>wrongId=true,ping:()=>ping=true,expire:()=>processes.clear(),
  run:async(name,args={})=>JSON.parse(JSON.stringify(await sandbox.solvitPlugin.tools[name](args,ctx))),
  page:async(action,fields={},nodeId=action)=>JSON.parse(JSON.stringify(await sandbox.solvitPlugin.pages.main({locale:'zh',event:action?{action,fields,nodeId}:null},ctx)))};
}
test('local MCP negotiates, handles server ping and keeps chat generations separate',async()=>{
 const f=fixture();f.ping();
 assert.equal((await f.run('mcp_call',{name:'write',arguments:{text:'one'}})).content[0].text,'chat-a:one');
 f.scope('chat-b');assert.equal((await f.run('mcp_servers')).servers[0].status,'not_connected');
 assert.equal((await f.run('mcp_call',{name:'write',arguments:{text:'two'}})).content[0].text,'chat-b:two');
 assert.equal(f.processes.size,2);f.scope('chat-a');
 assert.equal((await f.run('mcp_tools')).tools[0].name,'write');
 assert.equal(f.frames.filter(x=>x.method==='initialize').length,2);
 await f.run('mcp_disconnect');assert.equal(f.processes.size,1);f.scope('chat-b');
 assert.equal((await f.run('mcp_servers')).servers[0].status,'connected');
 assert.ok(f.frames.some(x=>x.id==='server-ping'&&x.result));
 assert.ok(!JSON.stringify(await f.run('mcp_servers')).includes('keep-private'));
});
test('crashed native generation reconnects with a fresh handshake and never replays writes',async()=>{
 const f=fixture();await f.run('mcp_connect');f.expire();await f.run('mcp_tools');
 assert.equal(f.frames.filter(x=>x.method==='initialize').length,2);f.fail();
 await assert.rejects(()=>f.run('mcp_call',{name:'write',arguments:{text:'once'}}),/disconnected/);
 assert.equal(f.frames.filter(x=>x.method==='tools/call').length,1);assert.equal(f.processes.size,0);
});
test('unexpected response IDs poison the connection and workspace absence never launches',async()=>{
 const f=fixture();f.scope(null);assert.equal((await f.run('mcp_servers')).servers[0].status,'workspace_required');
 await assert.rejects(()=>f.run('mcp_connect'),/workspace/);assert.equal(f.frames.length,0);
 f.scope('chat-a');f.wrongId();await assert.rejects(()=>f.run('mcp_connect'),/response ID/);assert.equal(f.processes.size,0);
});
test('local page preserves encrypted environment, requires a reviewed host action and closes its probe',async()=>{
 const f=fixture();const initial=await f.page();const probe=flatten(initial).find(n=>n.id==='probe_0');
 assert.equal(probe.hostAction.kind,'stdio.connect');assert.ok(probe.confirm.includes('python3'));
 assert.ok(!JSON.stringify(initial).includes('keep-private'));
 await f.page('edit',{},'edit_0');const saved=await f.page('save',{name:'local',command:'python3',args:'["server.py"]',env:'',cwd:''});
 assert.equal(JSON.parse(f.settings.get('connections'))[0].env.TOKEN,'keep-private');assert.ok(!JSON.stringify(saved).includes('keep-private'));
 await f.page('probe',{},'probe_0');assert.equal(f.processes.size,0);
 f.scope(null);assert.equal(flatten(await f.page()).find(n=>n.id==='probe_0').enabled,false);
});
test('imports explicit stdio without executing and rejects path escapes or implicit command profiles',async()=>{
 const f=fixture();await f.page();await f.page('import');
 await f.page('import_save',{config:JSON.stringify({mcpServers:{local:{type:'stdio',command:'node',args:['server.mjs'],env:{TOKEN:'new-private'}}}})});
 assert.equal(JSON.parse(f.settings.get('connections'))[0].command,'node');assert.equal(f.frames.length,0);
 for(const raw of [{type:'stdio',command:'node',cwd:'../other'},{command:'node'},{type:'stdio',command:'node',env:{'BAD=KEY':'secret'}}]){
  await f.page('import');const result=await f.page('import_save',{config:JSON.stringify({mcpServers:{bad:raw}})});
  assert.ok(flatten(result).some(n=>n.id==='message'));assert.equal(JSON.parse(f.settings.get('connections'))[0].command,'node');await f.page('back');
 }
});

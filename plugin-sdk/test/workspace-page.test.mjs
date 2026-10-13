import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const pkg=JSON.parse(await readFile('../market-server/packages/dev.solvit.workspace.solvit.json','utf8'));
const clone=value=>JSON.parse(JSON.stringify(value));
function harness(bound=true){
 const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
 let view,approved,created=0,writes=0;
 const snapshot={workspaceId:bound?'ws':null,workspaces:bound?[{id:'ws',name:'Project'}]:[],shells:bound?[{id:'s',name:'Shell 1'}]:[],draftInstallAllowed:false};
 const files=Array.from({length:45},(_,i)=>({name:`file-${i}.txt`,path:`file-${i}.txt`,directory:false,bytes:3}));
 const queries=[];
 const ctx={workspacePage:{query:async(op,args={})=>{
  queries.push([op,clone(args)]);
  if(op==='snapshot')return clone(snapshot);
  if(op==='list')return {files:files.slice(args.offset,args.offset+40),total:files.length};
  if(op==='read')return {text:'old',sha256:'a'.repeat(64)};
  if(op==='drafts')return [{id:'draft',name:'Draft',pluginId:'example.draft',version:'1.0.0',sha256:'b'.repeat(64),permissions:['network']}];
  if(op==='draft')return {text:'<script>must stay text</script>',total:29,sha256:'b'.repeat(64)};
  throw Error('unsupported');
 },perform:async()=>{
  assert.ok(approved);
  if(approved.operation==='create'){created++;snapshot.workspaceId='created';snapshot.workspaces.push({id:'created',name:approved.arguments.name})}
  if(approved.operation==='write')writes++;
  return clone(snapshot);
 }} };
 const nodes=()=>{const result=[];const visit=n=>{result.push(n);(n.children??[]).forEach(visit)};[...view.nodes,...view.footer??[]].forEach(visit);return result};
 const render=async(event=null)=>view=clone(await sandbox.solvitPlugin.pages.main({locale:'en',event},ctx));
 const event=async(id,fields={})=>{const node=nodes().find(n=>n.id===id);assert.ok(node,`missing ${id}`);assert.notEqual(node.enabled,false);approved=node.hostAction;return render({nodeId:id,action:node.action,fields:{...Object.fromEntries(nodes().filter(n=>n.kind==='field').map(n=>[n.id,n.value])),...fields}})};
 return {render,event,nodes,queries,snapshot,get created(){return created},get writes(){return writes},get approved(){return approved}};
}
test('workspace HTML preserves immutable API4 package and previews create before any native mutation',async()=>{
 assert.equal(pkg.manifest.apiVersion,17);assert.equal(pkg.manifest.version,'1.3.0');assert.ok(pkg.resources['ui/main.js']);
 const old=JSON.parse(await readFile('../market-server/packages/legacy/dev.solvit.workspace-1.0.0.solvit.json','utf8'));assert.equal(old.manifest.apiVersion,4);
 const h=harness(false);await h.render();assert.equal(h.created,0);await h.event('create');await h.event('prepareCreate',{name:'New Project'});
 assert.equal(h.created,0);await h.event('cancel');assert.equal(h.created,0);
 await h.event('prepareCreate',{name:'New Project'});await h.event('confirm');assert.equal(h.created,1);assert.equal(h.approved.arguments.name,'New Project');
});
test('directory pagination and editor preview carry exact path, content and observed version',async()=>{
 const h=harness();await h.render();await h.event('directory');assert.equal(h.nodes().filter(n=>n.kind==='card').length,40);
 await h.event('next');assert.equal(h.nodes().filter(n=>n.kind==='card').length,5);await h.event('file_0');
 const literal='<script>window.executed=true</script>';await h.event('prepareSave',{path:'file-40.txt',content:literal});assert.equal(h.writes,0);
 await h.event('confirm');assert.equal(h.writes,1);assert.deepEqual(h.approved.arguments,{path:'file-40.txt',text:literal,expectedSha256:'a'.repeat(64)});
 assert.ok(h.queries.every(([op,args])=>!('workspaceId' in args)&&!('chatId' in args)));
});
test('invalid names and oversized text stay editable; downloaded pages cannot silently install drafts',async()=>{
 const h=harness();await h.render();await h.event('create');await h.event('prepareCreate',{name:''});assert.ok(h.nodes().some(n=>n.id==='name'));assert.equal(h.created,0);
 await h.event('home');await h.event('directory');await h.event('new');await h.event('prepareSave',{path:'new.txt',content:'界'.repeat(1400)});assert.ok(h.nodes().some(n=>n.id==='content'));assert.equal(h.writes,0);
 await h.event('backToList');await h.event('home');await h.event('drafts');await h.event('draft_0');assert.equal(h.nodes().find(n=>n.id==='prepareInstall').enabled,false);
});

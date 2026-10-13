import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
const pkg=JSON.parse(await readFile('../market-server/packages/dev.solvit.workspace.solvit.json','utf8'));
const clone=value=>JSON.parse(JSON.stringify(value));
function harness(bound=true){
 const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
 let view,approved,created=0,writes=0,draft=null,document='old';
 const snapshot={workspaceId:bound?'ws':null,workspaces:bound?[{id:'ws',name:'Project'}]:[],shells:bound?[{id:'s',name:'Shell 1'}]:[],draftInstallAllowed:false};
 const files=Array.from({length:45},(_,i)=>({name:`file-${i}.txt`,path:`file-${i}.txt`,directory:false,bytes:3}));
 const queries=[];
 const entryActions=[];
 const ctx={workspacePage:{query:async(op,args={})=>{
  queries.push([op,clone(args)]);
  if(op==='snapshot')return clone(snapshot);
  if(op==='list')return {files:files.slice(args.offset,args.offset+40),total:files.length};
  if(op==='read')return {text:'old',sha256:'a'.repeat(64)};
  if(op==='readChunk'){
   let end=Math.min(args.offset+4096,document.length);
   if(end<document.length&&/[\uD800-\uDBFF]/.test(document[end-1]))end--;
   return {text:document.slice(args.offset,end),offset:args.offset,nextOffset:end,total:document.length,sha256:'a'.repeat(64)};
  }
  if(op==='editBegin'){draft={id:'editor-draft',path:args.path,expectedSha256:args.expectedSha256,total:args.total,text:''};return {draftId:draft.id}}
  if(op==='editAppend'){
   assert.equal(args.draftId,draft.id);assert.equal(args.offset,draft.text.length);assert.ok(args.text.length<=4096);
   draft.text+=args.text;return {offset:draft.text.length};
  }
  if(op==='editFinish'){
   assert.equal(draft.text.length,draft.total);
   draft.sha256=createHash('sha256').update(draft.text).digest('hex');
   return {draftId:draft.id,path:draft.path,sha256:draft.sha256};
  }
  if(op==='editDiscard'){draft=null;return {discarded:true}}
  if(op==='drafts')return [{id:'draft',name:'Draft',pluginId:'example.draft',version:'1.0.0',sha256:'b'.repeat(64),permissions:['network']}];
  if(op==='draft')return {text:'<script>must stay text</script>',total:29,sha256:'b'.repeat(64)};
  throw Error('unsupported');
 },perform:async()=>{
  assert.ok(approved);
  if(approved.operation==='create'){created++;snapshot.workspaceId='created';snapshot.workspaces.push({id:'created',name:approved.arguments.name})}
  if(approved.operation==='write')writes++;
  if(approved.operation==='writeDraft'){
   assert.deepEqual(approved.arguments,{draftId:draft.id,path:draft.path,sha256:draft.sha256});writes++;document=draft.text;
  }
  if(['makeDirectory','moveEntry','deleteEntry'].includes(approved.operation))entryActions.push(clone(approved));
  return clone(snapshot);
 }} };
 const nodes=()=>{const result=[];const visit=n=>{result.push(n);(n.children??[]).forEach(visit)};[...view.nodes,...view.footer??[]].forEach(visit);return result};
 const render=async(event=null)=>view=clone(await sandbox.solvitPlugin.pages.main({locale:'en',event},ctx));
 const event=async(id,fields={})=>{const node=nodes().find(n=>n.id===id);assert.ok(node,`missing ${id}`);assert.notEqual(node.enabled,false);approved=node.hostAction;return render({nodeId:id,action:node.action,fields:{...Object.fromEntries(nodes().filter(n=>n.kind==='field').map(n=>[n.id,n.value])),...fields}})};
 return {render,event,nodes,queries,entryActions,snapshot,set document(value){document=value},get document(){return document},get draft(){return draft},get created(){return created},get writes(){return writes},get approved(){return approved}};
}
test('workspace HTML preserves immutable API4 package and previews create before any native mutation',async()=>{
 assert.equal(pkg.manifest.apiVersion,18);assert.equal(pkg.manifest.version,'1.4.0');assert.ok(pkg.resources['ui/main.js']);
 assert.ok(pkg.resources['ui/editor-NOTICE.txt'].includes('Permission is hereby granted'));
 const api17=await readFile('../market-server/packages/legacy/dev.solvit.workspace-1.3.0.solvit.json');
 assert.equal(createHash('sha256').update(api17).digest('hex'),'95531ef7faaf38df4f1d56954505c848243bdcb2e4a773ac588b74e471ff037b');
 assert.equal(pkg.manifest.pages.find(page=>page.id==='changes').context,'execution');
 assert.ok(pkg.resources['review/main.js']);assert.ok(pkg.resources['review/jsdiff-LICENSE.txt'].includes('BSD 3-Clause License'));
 const api16=JSON.parse(await readFile('../market-server/packages/legacy/dev.solvit.workspace-1.2.0.solvit.json','utf8'));
 assert.equal(api16.manifest.apiVersion,16);assert.equal(api16.manifest.version,'1.2.0');assert.equal(api16.manifest.pages.length,1);
 const old=JSON.parse(await readFile('../market-server/packages/legacy/dev.solvit.workspace-1.0.0.solvit.json','utf8'));assert.equal(old.manifest.apiVersion,4);
 const h=harness(false);await h.render();assert.equal(h.created,0);await h.event('create');await h.event('prepareCreate',{name:'New Project'});
 assert.equal(h.created,0);await h.event('cancel');assert.equal(h.created,0);
 await h.event('prepareCreate',{name:'New Project'});await h.event('confirm');assert.equal(h.created,1);assert.equal(h.approved.arguments.name,'New Project');
});
test('directory pagination and staged editor carry exact full content and opened version',async()=>{
 const h=harness();await h.render();await h.event('directory');assert.equal(h.nodes().filter(n=>n.kind==='card').length,40);
 await h.event('next');assert.equal(h.nodes().filter(n=>n.kind==='card').length,5);await h.event('file_0');
 const literal='<script>window.executed=true</script>';await h.event('prepareSave',{path:'file-40.txt',uploadTotal:String(literal.length)});assert.equal(h.writes,0);
 await h.event('uploadChunk',{uploadOffset:'0',content:literal});await h.event('finishUpload');assert.equal(h.writes,0);
 assert.equal(h.draft.expectedSha256,'a'.repeat(64));await h.event('confirm');assert.equal(h.writes,1);assert.equal(h.document,literal);
 assert.ok(h.queries.every(([op,args])=>!('workspaceId' in args)&&!('chatId' in args)));
});
test('invalid names and oversized text stay editable; downloaded pages cannot silently install drafts',async()=>{
 const h=harness();await h.render();await h.event('create');await h.event('prepareCreate',{name:''});assert.ok(h.nodes().some(n=>n.id==='name'));assert.equal(h.created,0);
 await h.event('home');await h.event('directory');await h.event('new');await h.event('prepareSave',{path:'new.txt',uploadTotal:'262145'});assert.ok(h.nodes().some(n=>n.id==='content'));assert.equal(h.writes,0);
 await h.event('backToList');await h.event('home');await h.event('drafts');await h.event('draft_0');assert.equal(h.nodes().find(n=>n.id==='prepareInstall').enabled,false);
});
test('large Unicode preview and save cross only bounded events and cancel keeps files unchanged',async()=>{
 const h=harness();const original='a'.repeat(4095)+'🙂'+'完整代码\n'.repeat(8000);h.document=original;
 await h.render();await h.event('directory');await h.event('file_0');
 let complete='';
 while(true){
  const meta=JSON.parse(h.nodes().find(n=>n.id==='document_meta').text);
  complete+=h.nodes().find(n=>n.id==='document_chunk').text;
  if(meta.phase==='ready')break;
  await h.event('readNext');
 }
 assert.equal(complete,original);
 const changed=original+'\n// edited';
 await h.event('prepareSave',{path:'file-0.txt',uploadTotal:String(changed.length)});
 for(let offset=0;offset<changed.length;){
  let end=Math.min(offset+4096,changed.length);if(end<changed.length&&/[\uD800-\uDBFF]/.test(changed[end-1]))end--;
  await h.event('uploadChunk',{uploadOffset:String(offset),content:changed.slice(offset,end)});offset=end;
 }
 await h.event('finishUpload');assert.equal(h.document,original);await h.event('cancel');
 assert.equal(h.document,original);assert.equal(h.draft,null);assert.equal(h.writes,0);
 assert.ok(h.queries.filter(([op])=>op==='editAppend').every(([,args])=>args.text.length<=4096));
});
test('folder creation, rename and deletion consume only their displayed relative paths after confirmation',async()=>{
 const h=harness();await h.render();await h.event('directory');await h.event('mkdir');
 await h.event('prepareDirectory',{destination:'new-folder'});assert.equal(h.entryActions.length,0);
 await h.event('cancel');assert.equal(h.entryActions.length,0);
 await h.event('prepareDirectory',{destination:'new-folder'});await h.event('confirm');
 assert.deepEqual(h.entryActions.at(-1),{kind:'workspace.perform',operation:'makeDirectory',arguments:{path:'new-folder'}});
 await h.event('rename_0');await h.event('prepareMove',{destination:'renamed.txt'});assert.equal(h.entryActions.length,1);
 await h.event('confirm');assert.deepEqual(h.entryActions.at(-1).arguments,{path:'file-0.txt',destination:'renamed.txt'});
 await h.event('delete_0');assert.equal(h.entryActions.length,2);assert.ok(h.nodes().some(n=>n.id==='warning'));
 await h.event('cancel');assert.equal(h.entryActions.length,2);
 await h.event('delete_0');await h.event('confirm');assert.deepEqual(h.entryActions.at(-1),{kind:'workspace.perform',operation:'deleteEntry',arguments:{path:'file-0.txt'}});
});

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
const pkg=JSON.parse(await readFile('../market-server/packages/dev.solvit.workspace.solvit.json','utf8'));
const clone=value=>JSON.parse(JSON.stringify(value));
function harness(bound=true,directories=null){
 const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
 let view,approved,created=0,writes=0,draft=null,document='old',importSelection=null,nextSelection=null,importConflict=false;
 const imports=[];
 let importReview=null,reviewTarget=null,reviewSequence=0;
 const savedFiles=Array.from({length:25},(_,i)=>({messageId:`message-${25-i}`,messageNumber:25-i,attachmentId:`attachment-${i}`,name:`saved-${i}.txt`,mimeType:'text/plain',bytes:2048,sha256:'f'.repeat(64)}));
 const snapshot={workspaceId:bound?'ws':null,workspaces:bound?[{id:'ws',name:'Project'}]:[],shells:bound?[{id:'s',name:'Shell 1'}]:[],draftInstallAllowed:false};
 const files=Array.from({length:45},(_,i)=>({name:`file-${i}.txt`,path:`file-${i}.txt`,directory:false,bytes:3}));
 const queries=[];
 const entryActions=[];
 const ctx={workspacePage:{query:async(op,args={})=>{
  queries.push([op,clone(args)]);
  if(op==='snapshot')return clone(snapshot);
  if(op==='importSelection')return clone(importSelection);
  if(op==='reviewImport'){
   assert.equal(args.id,importSelection.id);assert.equal(args.sha256,importSelection.sha256);
   importReview={reviewId:`review-${++reviewSequence}`,path:args.path,target:clone(reviewTarget)};
   return clone(importReview);
  }
  if(op==='chatAttachments')return {attachments:clone(savedFiles.slice(args.offset,args.offset+20)),total:savedFiles.length};
  if(op==='importText'){
   assert.equal(args.id,importSelection.id);assert.equal(args.sha256,importSelection.sha256);
   return {kind:'TEXT',text:'<script>window.executed=true</script>\n代码🙂',truncated:true};
  }
  if(op==='importPdf'){
   assert.equal(args.id,importSelection.id);assert.equal(args.sha256,importSelection.sha256);
   assert.ok(Number.isInteger(args.page)&&args.page>=1&&args.page<=2);
   return {page:args.page,pageCount:2,text:`<script>PAGE ${args.page}</script>`,truncated:args.page===2,width:12,height:8,source:'data:image/jpeg;base64,/9gq/9k='};
  }
  if(op==='importPicture'){
   assert.equal(args.id,importSelection.id);assert.equal(args.sha256,importSelection.sha256);
   return {width:12,height:8,source:'data:image/jpeg;base64,/9gq/9k='};
  }
  if(op==='list'){const entries=directories?.[args.path]??files;return {files:entries.slice(args.offset,args.offset+40),total:entries.length}}
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
  if(approved.operation==='selectChatAttachment'){
   const args=approved.arguments,entry=savedFiles.find(e=>e.messageId===args.messageId&&e.attachmentId===args.attachmentId);
   assert.ok(entry);assert.equal(args.sha256,entry.sha256);
   importSelection={id:'saved-choice',name:entry.name,mimeType:entry.mimeType,bytes:entry.bytes,sha256:entry.sha256,source:{kind:'chatAttachment',messageId:entry.messageId,attachmentId:entry.attachmentId}};
   return clone(snapshot);
  }
  if(approved.operation==='selectImport'){if(nextSelection)importSelection=clone(nextSelection);return clone(nextSelection)}
  if(approved.operation==='dropImport'){assert.equal(approved.arguments.id,importSelection.id);importSelection=null;return clone(snapshot)}
  if(approved.operation==='importReviewed'){
   assert.equal(approved.arguments.id,importSelection.id);assert.equal(approved.arguments.sha256,importSelection.sha256);
   assert.equal(approved.arguments.reviewId,importReview.reviewId);assert.equal(approved.arguments.path,importReview.path);
   if(importConflict)throw Error('File already exists');
   const {reviewId,...args}=approved.arguments;
   imports.push(clone(args));importSelection=null;
   if(importReview.target)snapshot.lastImport={path:args.path,sha256:args.sha256,backupPath:'solvit-backup-original'};
   importReview=null;
  }
  if(approved.operation==='create'){created++;snapshot.workspaceId='created';snapshot.workspaces.push({id:'created',name:approved.arguments.name})}
  if(approved.operation==='write')writes++;
  if(approved.operation==='writeDraft'){
   assert.deepEqual(approved.arguments,{draftId:draft.id,path:draft.path,sha256:draft.sha256});writes++;document=draft.text;
  }
  if(['makeDirectory','moveEntry','deleteEntry'].includes(approved.operation))entryActions.push(clone(approved));
  return clone(snapshot);
 }} };
 const nodes=()=>{const result=[];const visit=n=>{result.push(n);(n.children??[]).forEach(visit)};[...view.nodes,...view.footer??[]].forEach(visit);return result};
 const render=async(event=null)=>{
  view=clone(await sandbox.solvitPlugin.pages.main({locale:'en',event},ctx));
  assert.ok((view.footer??[]).length<=6,'page exceeds the Android host footer limit');
  assert.ok((view.footer??[]).every(node=>node.kind==='button'));
  return view;
 };
 const event=async(id,fields={})=>{const node=nodes().find(n=>n.id===id);assert.ok(node,`missing ${id}`);assert.notEqual(node.enabled,false);approved=node.hostAction;return render({nodeId:id,action:node.action,fields:{...Object.fromEntries(nodes().filter(n=>n.kind==='field').map(n=>[n.id,n.value])),...fields}})};
 return {render,event,nodes,queries,entryActions,snapshot,imports,savedFiles,set nextSelection(value){nextSelection=value},set importConflict(value){importConflict=value},set reviewTarget(value){reviewTarget=value},set document(value){document=value},get document(){return document},get draft(){return draft},get created(){return created},get writes(){return writes},get approved(){return approved}};
}
test('file imports preserve metadata and require a separate reviewed create; conflicts stay renameable',async()=>{
 const h=harness();await h.render();
 h.nextSelection={id:'chosen',name:'source.js',mimeType:'text/javascript',bytes:30000,sha256:'c'.repeat(64)};
 await h.event('selectImport');assert.equal(h.imports.length,0);
 assert.equal(h.nodes().find(n=>n.id==='import_type').text,'text/javascript · 30000 B');
 assert.ok(h.nodes().some(n=>n.id==='import_sha'&&n.text==='c'.repeat(64)));
 await h.event('importPreview',{importPath:'kept.pdf'});
 assert.ok(h.nodes().find(n=>n.id==='import_content').text.includes('<script>'));
 assert.ok(h.nodes().some(n=>n.id==='import_truncated'));
 assert.equal(h.nodes().find(n=>n.id==='importPath').value,'kept.pdf');assert.equal(h.imports.length,0);
 await h.event('prepareImport',{importPath:'../escape.pdf'});assert.equal(h.imports.length,0);assert.ok(h.nodes().some(n=>n.id==='importPath'));
 await h.event('prepareImport',{importPath:'src/source.js'});assert.equal(h.imports.length,0);
 await h.event('cancel');assert.equal(h.nodes().find(n=>n.id==='importPath').value,'src/source.js');
 h.nextSelection=null;await h.event('selectImport');assert.equal(h.nodes().find(n=>n.id==='import_name').text,'source.js');
 await h.event('prepareImport',{importPath:'existing.pdf'});h.importConflict=true;await h.event('confirm');
 assert.equal(h.imports.length,0);assert.ok(h.nodes().some(n=>n.id==='message'&&n.text.includes('File already exists')));
 assert.ok(h.nodes().some(n=>n.id==='importPath'));h.importConflict=false;await h.event('prepareImport',{importPath:'renamed.pdf'});await h.event('confirm');
 assert.deepEqual(h.imports,[{id:'chosen',path:'renamed.pdf',sha256:'c'.repeat(64)}]);
 assert.ok(h.nodes().some(n=>n.id==='directory'));
 const unbound=harness(false);await unbound.render();assert.ok(!unbound.nodes().some(n=>n.id==='selectImport'));
});
test('existing destination shows its reviewed version; cancel does not write and stale approval requires fresh review',async()=>{
 const h=harness();await h.render();
 h.nextSelection={id:'replacement',name:'data.bin',mimeType:'application/octet-stream',bytes:32,sha256:'c'.repeat(64)};
 h.reviewTarget={bytes:51,sha256:'a'.repeat(64)};
 await h.event('selectImport');await h.event('prepareImport');
 assert.equal(h.nodes().find(n=>n.id==='replace_sha').text,'a'.repeat(64));
 assert.equal(h.nodes().find(n=>n.id==='replace_size').text,'51 B');
 assert.equal(h.nodes().find(n=>n.id==='confirm').text,'Replace and keep original');
 assert.ok(h.nodes().find(n=>n.id==='replace_warning').text.includes('solvit-backup-'));
 const first=h.nodes().find(n=>n.id==='confirm').hostAction.arguments.reviewId;
 await h.event('cancel');assert.equal(h.imports.length,0);
 await h.event('prepareImport');assert.notEqual(h.nodes().find(n=>n.id==='confirm').hostAction.arguments.reviewId,first);
 h.importConflict=true;await h.event('confirm');assert.equal(h.imports.length,0);
 assert.ok(!h.nodes().some(n=>n.id==='confirm'));assert.ok(h.nodes().some(n=>n.id==='importPath'));
 h.importConflict=false;h.reviewTarget={bytes:70,sha256:'b'.repeat(64)};
 await h.event('prepareImport');assert.equal(h.nodes().find(n=>n.id==='replace_sha').text,'b'.repeat(64));
 await h.event('confirm');assert.equal(h.imports.length,1);
 assert.ok(h.nodes().find(n=>n.id==='message').text.includes('solvit-backup-original'));
});
test('discarding an import selection never submits a write',async()=>{
 const h=harness();await h.render();h.nextSelection={id:'chosen',name:'file.bin',mimeType:'application/octet-stream',bytes:0,sha256:'d'.repeat(64)};
 await h.event('selectImport');await h.event('dropImport');assert.equal(h.imports.length,0);assert.ok(h.nodes().some(n=>n.id==='directory'));
});
test('PDF navigation keeps original selection and path, rejects out-of-range pages, and never imports on preview',async()=>{
 const h=harness();await h.render();h.nextSelection={id:'pdf',name:'pages.pdf',mimeType:'application/pdf',bytes:2048,sha256:'f'.repeat(64)};
 await h.event('selectImport');assert.ok(!h.nodes().some(n=>n.id==='importPreview'));
 await h.event('importPdf',{importPath:'documents/pages.pdf'});
 assert.equal(h.imports.length,0);assert.equal(h.nodes().find(n=>n.id==='pdfPrev').enabled,false);
 assert.equal(h.nodes().find(n=>n.id==='importPath').value,'documents/pages.pdf');
 assert.equal(h.nodes().find(n=>n.id==='pdf_page_text').text,'<script>PAGE 1</script>');
 await h.event('pdfNext');assert.ok(h.nodes().some(n=>n.id==='pdf_truncated'));
 assert.equal(h.nodes().find(n=>n.id==='pdfNext').enabled,false);
 const previousReads=h.queries.filter(([op])=>op==='importPdf').length;
 await h.event('pdfGo',{pdfPageNumber:'3'});assert.equal(h.queries.filter(([op])=>op==='importPdf').length,previousReads);
 await h.event('pdfPrev');assert.equal(h.nodes().find(n=>n.id==='pdf_page_text').text,'<script>PAGE 1</script>');
 await h.event('prepareImport');await h.event('cancel');assert.ok(h.nodes().some(n=>n.id==='import_pdf_picture'));
 h.nextSelection=null;await h.event('selectImport');assert.ok(h.nodes().some(n=>n.id==='import_pdf_picture'));
 await h.event('prepareImport');await h.event('confirm');
 assert.deepEqual(h.imports,[{id:'pdf',path:'documents/pages.pdf',sha256:'f'.repeat(64)}]);
 assert.ok(!h.nodes().some(n=>n.id==='import_pdf_picture'));
});
test('image preview remains separate from original import and preserves its destination',async()=>{
 const h=harness();await h.render();h.nextSelection={id:'picture',name:'photo.png',mimeType:'image/png',bytes:300000,sha256:'e'.repeat(64)};
 await h.event('selectImport');assert.ok(!h.nodes().some(n=>n.id==='importPreview'));
 await h.event('importPicture',{importPath:'images/photo.png'});
 assert.equal(h.nodes().find(n=>n.id==='importPath').value,'images/photo.png');
 assert.deepEqual(h.nodes().find(n=>n.id==='import_picture'),{id:'import_picture',kind:'image',source:'data:image/jpeg;base64,/9gq/9k=',alt:'photo.png',width:12,height:8});
 assert.equal(h.imports.length,0);await h.event('prepareImport');await h.event('confirm');
 assert.deepEqual(h.imports,[{id:'picture',path:'images/photo.png',sha256:'e'.repeat(64)}]);
 assert.ok(!h.nodes().some(n=>n.id==='import_picture'));
});
test('workspace HTML preserves immutable API4 package and previews create before any native mutation',async()=>{
 assert.equal(pkg.manifest.apiVersion,25);assert.equal(pkg.manifest.version,'1.8.0');assert.ok(pkg.resources['ui/main.js']);
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
test('nested paginated directories keep every action within the Android footer contract',async()=>{
 const entries=Array.from({length:85},(_,i)=>({name:`file-${i}.txt`,path:`src/file-${i}.txt`,directory:false,bytes:3}));
 const h=harness(true,{'':[{name:'src',path:'src',directory:true,bytes:0}],src:entries});
 await h.render();await h.event('directory');await h.event('file_0');
 assert.equal(h.nodes().find(n=>n.id==='directory').text,'src');
 await h.event('next');
 for(const id of ['home','refresh','parent','prev','next','new','mkdir','selectImport','chatFiles'])
  assert.ok(h.nodes().some(node=>node.id===id),`missing directory action ${id}`);
 assert.equal(h.nodes().filter(node=>node.kind==='card').length,40);
 await h.event('next');assert.equal(h.nodes().filter(node=>node.kind==='card').length,5);
 await h.event('prev');await h.event('parent');
 assert.equal(h.nodes().find(n=>n.id==='directory').text,'/');
 assert.equal(h.writes,0);
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
test('lazy tree keeps the opened document while loading siblings and only opens discovered entries',async()=>{
 const entry=(path,directory=false)=>({name:path.split('/').pop(),path,directory,bytes:directory?0:3});
 const h=harness(true,{'':[entry('src',true),entry('docs',true)],src:[entry('src/code.ts')],docs:[entry('docs/readme.md')]});
 const tree=()=>JSON.parse(h.nodes().find(n=>n.id==='tree_meta').text);
 await h.render();await h.event('tree_load',{treePath:'',treeOffset:'0'});assert.equal(tree().result.entries.length,2);
 await h.event('tree_load',{treePath:'src',treeOffset:'0'});await h.event('tree_open',{treeTarget:'src/code.ts'});
 const opened=JSON.parse(h.nodes().find(n=>n.id==='document_meta').text);assert.equal(opened.path,'src/code.ts');
 await h.event('tree_load',{treePath:'docs',treeOffset:'0'});
 const same=JSON.parse(h.nodes().find(n=>n.id==='document_meta').text);assert.equal(same.editorId,opened.editorId);assert.equal(same.path,opened.path);
 await h.event('tree_open',{treeTarget:'../outside.ts'});
 assert.equal(JSON.parse(h.nodes().find(n=>n.id==='document_meta').text).path,opened.path);
 assert.equal(h.writes,0);assert.equal(h.created,0);
});
test('tree pagination remains bounded and never creates an unbound workspace',async()=>{
 const h=harness();await h.render();let paths=[];
 for(let offset=0;offset<45;offset+=10){
  await h.event('tree_load',{treePath:'',treeOffset:String(offset)});
  const result=JSON.parse(h.nodes().find(n=>n.id==='tree_meta').text).result;
  assert.ok(result.entries.length<=10);paths.push(...result.entries.map(entry=>entry.path));
 }
 assert.equal(paths.length,45);assert.equal(new Set(paths).size,45);
 await h.event('directory');assert.ok(h.nodes().length<=256);
 const empty=harness(false);await empty.render();assert.equal(JSON.parse(empty.nodes().find(n=>n.id==='tree_meta').text).workspaceId,null);assert.equal(empty.created,0);
});

test('saved chat attachments paginate, show provenance and need a separate original import confirmation',async()=>{
 const h=harness();await h.render();await h.event('chatFiles');
 assert.equal(h.nodes().filter(n=>n.id.startsWith('chat_file_name_')).length,20);
 await h.event('chatFilesNext');assert.equal(h.nodes().filter(n=>n.id.startsWith('chat_file_name_')).length,5);
 await h.event('chatFilesPrev');await h.event('chat_file_select_0');
 assert.deepEqual(h.approved.arguments,{messageId:'message-25',attachmentId:'attachment-0',sha256:'f'.repeat(64)});
 assert.equal(h.imports.length,0);assert.ok(h.nodes().find(n=>n.id==='import_source').text.includes('message-25'));
 await h.event('importPreview',{importPath:'kept.txt'});assert.equal(h.imports.length,0);
 await h.event('prepareImport');await h.event('cancel');assert.equal(h.nodes().find(n=>n.id==='importPath').value,'kept.txt');
 await h.event('chatFiles');await h.event('chatFilesBack');assert.equal(h.nodes().find(n=>n.id==='importPath').value,'kept.txt');
 await h.event('prepareImport');await h.event('confirm');
 assert.deepEqual(h.imports,[{id:'saved-choice',path:'kept.txt',sha256:'f'.repeat(64)}]);
 for(const mimeType of ['image/png','application/pdf']){
  const p=harness();p.savedFiles[0].mimeType=mimeType;await p.render();await p.event('chatFiles');await p.event('chat_file_select_0');
  await p.event(mimeType==='application/pdf'?'importPdf':'importPicture');assert.equal(p.imports.length,0);
  assert.ok(p.nodes().some(n=>n.id===(mimeType==='application/pdf'?'import_pdf_picture':'import_picture')));
 }
 const empty=harness();empty.savedFiles.splice(0);await empty.render();await empty.event('chatFiles');assert.ok(empty.nodes().some(n=>n.id==='chat_files_empty'));
 const noWorkspace=harness(false);await noWorkspace.render();assert.ok(!noWorkspace.nodes().some(n=>n.id==='chatFiles'));
});

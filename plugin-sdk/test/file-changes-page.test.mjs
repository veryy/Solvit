import {test} from 'node:test';
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
const bundle=await build({entryPoints:['../builtin-plugins/workspace/changes.ts'],bundle:true,write:false,
 platform:'neutral',format:'iife',globalName:'changesModule',target:'es2020'});
const digest=text=>createHash('sha256').update(text).digest('hex');
function harness(before,after,{missing=false}={}){
 const sandbox=vm.createContext({});vm.runInContext(bundle.outputFiles[0].text,sandbox);
 const renderPage=sandbox.changesModule.changesPage();let view,failed=false;
 const observation=text=>({kind:text===null?'ABSENT':'TEXT',sha256:text===null?null:digest(text),bytes:text===null?null:Buffer.byteLength(text),captured:text!==null});
 const review={complete:true,pending:false,beforeAt:1,afterAt:2,changes:[{index:0,path:'src/code.txt',kind:'MODIFIED',before:observation(before),after:observation(after)}]};
 const queries=[];
 const ctx={workspacePage:{query:async(operation,args={})=>{
  queries.push([operation,{...args}]);if(failed)throw Error('temporary failure');
  if(operation==='changes')return review;
  assert.equal(operation,'changeContent');assert.deepEqual(Object.keys(args).sort(),['index','offset','side']);assert.equal(args.index,0);
  const source=args.side==='before'?before:after,body=source??'';
  const meta=review.changes[0][args.side];
  if(missing&&source!==null)return {available:false,kind:meta.kind,sha256:meta.sha256,text:null,offset:args.offset,nextOffset:0,total:null};
  let end=Math.min(body.length,args.offset+4096);if(end<body.length&&/[\uD800-\uDBFF]/.test(body[end-1]))end--;
  return {available:true,kind:meta.kind,sha256:meta.sha256,text:body.slice(args.offset,end),offset:args.offset,nextOffset:end,total:body.length};
 },perform:async()=>{throw Error('Read-only page must never mutate')}}};
 const render=async(event=null)=>view=await renderPage({locale:'en',event},ctx);
 const event=async(id)=>{const node=[...view.nodes,...view.footer].find(n=>n.id===id);assert.ok(node,`missing ${id}`);return render({nodeId:id,action:node.action,fields:{}})};
 const rows=()=>view.nodes.filter(n=>n.id.startsWith('diffRow_')).map(n=>JSON.parse(n.text));
 return {render,event,rows,queries,review,get view(){return view},set failed(value){failed=value}};
}
test('plugin page obtains complete captured bodies in chunks and exposes the late edit',async()=>{
 const before=Array.from({length:2000},(_,i)=>`old ${i}`).join('\n')+'\n';const after=before.replace('old 1998','TAIL-CHANGE');
 const h=harness(before,after);await h.render();await h.event('change_0');
 assert.ok(h.rows().some(r=>r.operation==='added'&&r.text==='TAIL-CHANGE'));
 assert.ok(h.queries.filter(([op])=>op==='changeContent').length>2);
 assert.ok(h.queries.every(([,args])=>!('runId'in args)&&!('path'in args)&&!('sessionId'in args)));
 const readCount=h.queries.length;await h.render();assert.equal(h.queries.length,readCount);
});
test('failed reads can be retried and unavailable legacy captures never use current files',async()=>{
 const h=harness('old','new');await h.render();h.failed=true;await h.event('change_0');
 assert.ok(h.view.nodes.some(n=>n.id==='failure'));assert.equal(h.rows().length,0);
 h.failed=false;await h.event('retry');assert.ok(h.rows().some(r=>r.text==='new'));assert.ok(!h.view.nodes.some(n=>n.id==='failure'));
 const legacy=harness('old','new',{missing:true});await legacy.render();await legacy.event('change_0');
 assert.ok(legacy.view.nodes.some(n=>n.id==='missing'));assert.equal(legacy.rows().length,0);
 assert.ok(!legacy.queries.some(([op])=>op==='read'));
});
test('created files compare against actual absence and retain literal HTML text',async()=>{
 const h=harness(null,'<script>must remain text</script>\n');await h.render();await h.event('change_0');
 assert.ok(h.rows().some(r=>r.operation==='added'&&r.text==='<script>must remain text</script>'));
 assert.ok(!h.view.nodes.some(n=>n.id==='missing'));
});
test('every segment of a maximum size escaped line remains accessible within native view limits',async()=>{
 const body='\u0001'.repeat(256*1024);const h=harness('',body);await h.render();await h.event('change_0');let all='';
 for(let page=0;page<1000;page++){
  assert.ok(Buffer.byteLength(JSON.stringify(h.view))<=128*1024);
  assert.ok(h.view.nodes.every(n=>n.kind!=='text'||n.text.length<=16384));
  all+=h.rows().filter(r=>r.operation==='added').map(r=>r.text).join('');
  if(!h.view.footer.some(n=>n.id==='next'))break;
  await h.event('next');
 }
 assert.equal(all,body);
});

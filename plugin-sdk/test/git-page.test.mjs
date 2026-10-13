import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import vm from 'node:vm';
import {buildPlugin} from '../scripts/build-plugin.mjs';

async function harness(){
 const dir=await mkdtemp(join(tmpdir(),'solvit-git-workbench-'));
 const output=join(dir,'git.json');
 await buildPlugin(resolve('../builtin-plugins/git/index.ts'),resolve('../builtin-plugins/git/manifest.json'),output);
 const pkg=JSON.parse(await readFile(output,'utf8')),sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
 const patch='a'.repeat(4095)+'😀\n';let writes=0;
 const calls=[];
 const ctx={gitPage:{query:async(op,args)=>{
  calls.push([op,args]);
  if(op==='status')return {branch:'refs/heads/main',head:'12345678',entries:[{path:'src/code.kt',staged:'modified',working:'modified',conflict:false}],total:1};
  if(op==='diff')return {reviewId:'native-review',path:args.path,staged:args.staged,total:patch.length};
  if(op==='diffChunk'){assert.equal(args.reviewId,'native-review');const end=args.offset===0?4095:patch.length;return {text:patch.slice(args.offset,end),offset:args.offset,nextOffset:end,total:patch.length}}
  if(op==='reviewCommit')return {revisionId:'native-index-review'};
  if(op==='history')return [{id:'abcdef012345',message:'Reviewed commit',author:'Fixture',timeSeconds:1}];
  throw Error('Unknown Git query '+op);
 },perform:async(...args)=>{assert.equal(args.length,0);writes++;return {id:'new-commit'}}},workspacePage:{query:async(op)=>{
  assert.equal(op,'snapshot');return {workspaceId:'bound',workspaces:[{id:'bound',name:'Shared directory'}],shells:[],draftInstallAllowed:false};
 },perform:async()=>{throw Error('No file writes expected')}}};
 const page=sandbox.solvitPlugin.pages.main;
 const input=(event=null)=>({locale:'zh',event});
 const event=(nodeId,action,fields={})=>({nodeId,action,fields});
 return {pkg,ctx,page,input,event,calls,writes:()=>writes,close:()=>rm(dir,{recursive:true})};
}

test('Git workbench keeps staged and working diffs separate, reads the full patch and confirms only native handles',async()=>{
 const h=await harness();try{
  const initial=await h.page(h.input(),h.ctx);
  assert.equal(h.writes(),0);assert.ok(initial.nodes.some(n=>n.id==='git_file_0'));
  let diff=await h.page(h.input(h.event('git_working_0','gitDiffWorking')),h.ctx);
  assert.equal(h.calls.find(c=>c[0]==='diff')[1].staged,false);
  assert.equal(diff.footer.find(n=>n.id==='git_stage').enabled,false);
  let chunk=diff.nodes.find(n=>n.id==='git_patch_chunk').text;
  diff=await h.page(h.input(h.event('git_read','gitReadNext')),h.ctx);
  chunk+=diff.nodes.find(n=>n.id==='git_patch_chunk').text;
  assert.equal(chunk,'a'.repeat(4095)+'😀\n');assert.equal(diff.footer.find(n=>n.id==='git_stage').enabled,true);
  const confirm=await h.page(h.input(h.event('git_stage','gitStage')),h.ctx);
  assert.equal(h.writes(),0);
  assert.deepEqual(JSON.parse(JSON.stringify(confirm.footer.find(n=>n.id==='git_confirm').hostAction)),
   {kind:'git.perform',operation:'stage',arguments:{reviewId:'native-review'}});
  await h.page(h.input(h.event('git_confirm','gitConfirm')),h.ctx);assert.equal(h.writes(),1);
  await h.page(h.input(h.event('git_staged_0','gitDiffStaged')),h.ctx);
  await h.page(h.input(h.event('git_read','gitReadNext')),h.ctx);
  const unstage=await h.page(h.input(h.event('git_stage','gitStage')),h.ctx);
  assert.equal(unstage.footer.find(n=>n.id==='git_confirm').hostAction.operation,'unstage');
 }finally{await h.close()}
});

test('Git commit uses the captured index handle and Files reuses the bound directory editor resources',async()=>{
 const h=await harness();try{
  await h.page(h.input(),h.ctx);await h.page(h.input(h.event('git_commit','gitCommit')),h.ctx);
  const view=await h.page(h.input(h.event('git_review','gitReviewCommit',{git_message:'Only reviewed files',git_author:'Fixture',git_email:'test@example.invalid'})),h.ctx);
  const action=view.footer.find(n=>n.id==='git_confirm').hostAction;
  assert.equal(action.operation,'commit');assert.equal(action.arguments.revisionId,'native-index-review');assert.equal(h.writes(),0);
  await h.page(h.input(h.event('git_confirm','gitConfirm')),h.ctx);assert.equal(h.writes(),1);
  const files=await h.page(h.input(h.event('git_files','gitFiles')),h.ctx);
  assert.ok(files.nodes.some(n=>n.id==='name'&&n.text==='Shared directory'));
  assert.ok(files.footer.some(n=>n.action==='gitChanges'));
  assert.ok(h.pkg.resources['ui/main.js'].includes('workspaceEditor'));
  assert.ok(h.pkg.resources['ui/editor-NOTICE.txt'].includes('MIT'));
 }finally{await h.close()}
});

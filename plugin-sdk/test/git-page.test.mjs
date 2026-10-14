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


test('Git AI tools forward the exact approved JSON to the shared host query capability',async()=>{
 const h=await harness();try{
  for(const [name,args]of [['status',{offset:10,limit:5}],['diff',{path:'src/code.kt',staged:true,offset:4095,expectedPatchSha256:'a'.repeat(64)}],['history',{limit:3}]]){
   let calls=0;
   const result=await h.pkg.manifest.tools.find(t=>t.name===name);assert.ok(result);
   const sandbox=vm.createContext({});vm.runInContext(h.pkg.source,sandbox);
   await sandbox.solvitPlugin.tools[name](args,{git:{call:async(op,approved)=>{
    calls++;assert.equal(op,name);assert.deepEqual(JSON.parse(JSON.stringify(approved)),args);return {native:true};
   }}});
   assert.equal(calls,1);
  }
 }finally{await h.close()}
});

test('Remote Git starts confirmed jobs, polls without blocking and sends only retained review handles',async()=>{
 const h=await harness();try{
  let starts=0,state={status:'RUNNING',progress:{task:'Receiving objects',completed:4,total:10}};
  const query=h.ctx.gitPage.query;
  h.ctx.gitPage.query=async(op,args)=>op==='remoteOperation'?(assert.equal(args.operationId,'native-job'),state):query(op,args);
  h.ctx.gitPage.perform=async()=>{starts++;return {operationId:'native-job'}};
  await h.page(h.input(),h.ctx);
  const remote=await h.page(h.input(h.event('git_remote','gitRemote')),h.ctx);
  assert.ok(remote.nodes.some(n=>n.id==='git_remote_url'));
  const confirm=await h.page(h.input(h.event('git_push','gitPush',{git_remote_url:'https://github.com/veryy/Solvit.git',git_remote_branch:'main'})),h.ctx);
  assert.equal(starts,0);
  assert.deepEqual(JSON.parse(JSON.stringify(confirm.footer.find(n=>n.id==='git_confirm').hostAction)),
   {kind:'git.perform',operation:'startRemote',arguments:{operation:'reviewPush',arguments:{url:'https://github.com/veryy/Solvit.git',branch:'main'}}});
  let running=await h.page(h.input(h.event('git_confirm','gitConfirm')),h.ctx);
  assert.equal(starts,1);assert.ok(running.nodes.some(n=>n.id==='git_transfer'));
  running=await h.page(h.input(h.event('git_remote_poll','gitRemotePoll')),h.ctx);
  assert.ok(running.nodes.find(n=>n.id==='git_transfer').text.includes('4/10'));
  assert.equal(running.footer.find(n=>n.id==='git_remote_cancel').hostAction.operation,'cancelTransfer');
  state={status:'SUCCEEDED',result:{reviewId:'native-push-review',localHead:'local-head',remoteHead:'remote-head'}};
  const reviewed=await h.page(h.input(h.event('git_remote_poll','gitRemotePoll')),h.ctx);
  assert.ok(reviewed.nodes.find(n=>n.id==='git_remote_head').text.includes('remote-head'));
  const push=await h.page(h.input(h.event('git_remote_apply','gitRemoteApply')),h.ctx);
  assert.deepEqual(JSON.parse(JSON.stringify(push.footer.find(n=>n.id==='git_confirm').hostAction)),
   {kind:'git.perform',operation:'startRemote',arguments:{operation:'push',arguments:{reviewId:'native-push-review'}}});
  await h.page(h.input(h.event('git_confirm','gitConfirm')),h.ctx);
  state={status:'SUCCEEDED',result:{status:'REJECTED_NONFASTFORWARD'}};
  const rejected=await h.page(h.input(h.event('git_remote_poll','gitRemotePoll')),h.ctx);
  assert.equal(rejected.nodes.find(n=>n.id==='git_error').text,'REJECTED_NONFASTFORWARD');
  assert.ok(h.pkg.resources['ui/main.js'].includes('git_remote_poll'));
 }finally{await h.close()}
});

test('Credentials page declares only the repository and delegates input to the native form',async()=>{
 const h=await harness();try{
  let inputs=0;h.ctx.gitPage.credentials=async(...args)=>{assert.equal(args.length,0);inputs++;return 'SAVED'};
  await h.page(h.input(),h.ctx);await h.page(h.input(h.event('git_remote','gitRemote')),h.ctx);
  const form=await h.page(h.input(h.event('git_credentials','gitCredentials',{git_remote_url:'https://github.com/veryy/Solvit.git'})),h.ctx);
  assert.equal(inputs,0);
  assert.deepEqual(JSON.parse(JSON.stringify(form.footer.find(n=>n.id==='git_credentials_confirm').hostAction)),
   {kind:'git.credentials',url:'https://github.com/veryy/Solvit.git'});
  assert.ok(form.nodes.every(n=>n.kind!=='field'));
  const saved=await h.page(h.input(h.event('git_credentials_confirm','gitCredentialsConfirm')),h.ctx);
  assert.equal(inputs,1);assert.ok(saved.nodes.some(n=>n.id==='git_remote_url'));
 }finally{await h.close()}
});

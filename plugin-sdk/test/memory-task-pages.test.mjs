import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,writeFile,readFile,rm } from 'node:fs/promises';
import { join,resolve } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { buildPlugin } from '../scripts/build-plugin.mjs';
const clone=v=>JSON.parse(JSON.stringify(v));
const flatten=v=>{const a=[];const visit=n=>{a.push(n);for(const c of n.children??[])visit(c)};for(const n of [...v.nodes,...v.footer??[]])visit(n);return a};
const task=i=>({id:`job-${i}`,status:'FAILED',attempt:1,updatedAt:100-i,failure:'MODEL',roleName:'Coder',model:'fixture',sessionId:'chat',userMessageId:`user-${i}`,assistantMessageId:`answer-${i}`});
async function fixture(run) {
  const dir=await mkdtemp(join(tmpdir(),'solvit-memory-task-page-'));
  try {
    await writeFile(join(dir,'entry.ts'),`import {createMemoryTasksPage} from ${JSON.stringify(resolve('../builtin-plugins/memory/task-pages.ts'))};export default {tools:{},pages:{main:createMemoryTasksPage()}};`);
    await writeFile(join(dir,'manifest.json'),JSON.stringify({id:'test.task-page',name:'Tasks',description:'Test',version:'1.0.0',apiVersion:9,permissions:['storage'],dependencies:[],tools:[],pages:[{id:'main',title:'Tasks'}]}));
    await buildPlugin(join(dir,'entry.ts'),join(dir,'manifest.json'),join(dir,'out.json'));
    const sandbox=vm.createContext({});vm.runInContext(JSON.parse(await readFile(join(dir,'out.json'),'utf8')).source,sandbox);
    const rows=Array.from({length:25},(_,i)=>task(i));let controls=0,changed=1,reject=false;
    const jobs={list:async({cursor})=>{
      const start=cursor?rows.findIndex(r=>r.id===cursor.id)+1:0;const page=rows.slice(start,start+20);
      return {available:true,jobs:clone(page),activeJobId:rows.find(r=>r.status==='RUNNING')?.id??null,
        nextCursor:page.length===20?{id:page.at(-1).id,updatedAt:page.at(-1).updatedAt}:null};
    },perform:async(...args)=>{assert.equal(args.length,0);controls++;if(reject)throw Error('fixture-secret-error');return {changed,receipts:0}}};
    const page=async(action,locale='zh',service=jobs)=>clone(await sandbox.solvitPlugin.pages.main({locale,event:action?{nodeId:action,action,fields:{}}:null},{jobs:service}));
    await run({page,rows,controls:()=>controls,setChanged:n=>{changed=n},reject:()=>{reject=true}});
  } finally {await rm(dir,{recursive:true});}
}
test('task page uses owner listing cursors and exact native retry/delete metadata',async()=>{
  await fixture(async({page})=>{
    let view=await page();assert.equal(flatten(view).filter(n=>n.id.startsWith('task_open_')).length,20);
    view=await page('task_next');assert.equal(flatten(view).filter(n=>n.id.startsWith('task_open_')).length,5);
    view=await page('task_open_0');assert.ok(flatten(view).some(n=>n.text==='来源用户消息: user-20'));
    assert.deepEqual(flatten(view).find(n=>n.id==='task_retry').hostAction,{kind:'postTurn.retry',jobId:'job-20',attempt:1});
    assert.deepEqual(flatten(view).find(n=>n.id==='task_delete').hostAction,{kind:'postTurn.delete',jobId:'job-20',attempt:1,status:'FAILED'});
    await page('task_back');view=await page('task_previous');assert.equal(flatten(view).filter(n=>n.id.startsWith('task_open_')).length,20);
  });
});
test('running tasks cannot be deleted and retry limit stays visible in plugin controls',async()=>{
  await fixture(async({page,rows,controls,setChanged})=>{
    rows[0].status='RUNNING';rows[0].failure=null;rows[1].attempt=3;
    await page();let view=await page('task_open_0');
    assert.ok(!flatten(view).some(n=>n.id==='task_delete'||n.id==='task_retry'));
    assert.deepEqual(flatten(view).find(n=>n.id==='task_stop').hostAction,{kind:'postTurn.stop',jobId:'job-0',attempt:1});
    await page('task_back');view=await page('task_open_1');assert.ok(!flatten(view).some(n=>n.id==='task_retry'));
    setChanged(0);view=await page('task_control');assert.equal(controls(),1);assert.ok(flatten(view).some(n=>n.text?.includes('状态已变化')));
  });
});
test('task UI supplies six languages and reports missing service without fake controls',async()=>{
  await fixture(async({page})=>{
    for(const [locale,title] of [['zh','自动记忆任务'],['en','Background memory tasks'],['ja','記憶のバックグラウンドタスク'],['ko','기억 백그라운드 작업'],['es','Tareas de memoria en segundo plano'],['fr','Tâches de mémoire en arrière-plan']])assert.equal((await page(undefined,locale)).title,title);
    const view=await page(undefined,'zh',null);assert.ok(flatten(view).some(n=>n.id==='unavailable'));
    assert.ok(!flatten(view).some(n=>n.hostAction));
  });
});
test('task errors show safe plugin text and cleanup carries a native control declaration',async()=>{
  await fixture(async({page,reject})=>{
    const initial=await page();assert.deepEqual(flatten(initial).find(n=>n.id==='task_cleanup').hostAction,{kind:'postTurn.cleanup'});
    reject();const view=await page('task_control');assert.ok(flatten(view).some(n=>n.text?.includes('操作未完成')));
    assert.ok(!JSON.stringify(view).includes('fixture-secret-error'));
  });
});

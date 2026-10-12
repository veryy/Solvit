import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { buildPlugin } from '../scripts/build-plugin.mjs';

const clone = value => JSON.parse(JSON.stringify(value));
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'solvit-memory-page-storage-'));
  try {
    const entry = join(dir, 'entry.ts'), manifest = join(dir, 'manifest.json'), output = join(dir, 'output.json');
    await writeFile(entry, `import {createMemoryPageBackend} from ${JSON.stringify(resolve('../builtin-plugins/memory/page-storage.ts'))};
      const backend=createMemoryPageBackend(ctx=>ctx.storage.get('memory.v1'), (db,ctx)=>ctx.storage.set('memory.v1',db).then(()=>{}));
      export default {tools:{save:(args,ctx)=>backend.save(args,ctx),remove:(args,ctx)=>backend.remove(args,ctx).then(()=>null)}};`);
    await writeFile(manifest, JSON.stringify({ id:'test.memory-storage',name:'Storage',description:'Test',version:'1.0.0',apiVersion:9,
      permissions:['storage'],dependencies:[],tools:['save','remove'].map(name=>({name,description:name,inputSchema:{type:'object'}})) }));
    await buildPlugin(entry, manifest, output);
    const sandbox = vm.createContext({}); vm.runInContext(JSON.parse(await readFile(output,'utf8')).source,sandbox);
    const source = {turnId:'answer',assistantMessageId:'answer',userMessageId:'question',sessionId:'chat',roleId:null,roleName:null,quote:'I use Vim'};
    let db = {format:1,sequence:2,facts:[{id:'m1',text:'Uses Vim',category:'preference',tags:['old'],createdAt:'before',updatedAt:'before',source},
      {id:'m2',text:'Another fact',category:'fact',tags:[],createdAt:'before',updatedAt:'before'}],
      edges:[{from:'m1',to:'m2',relation:'related'}],chunks:[],profile:{},completedSources:[{...source,factIds:['m1']}]};
    let interfere, writes=0;
    const ctx = {storage:{get:async()=>clone(db),compareAndSet:async(key,expected,next)=>{
      if(interfere){const callback=interfere;interfere=undefined;callback(db);}
      if(JSON.stringify(db)!==JSON.stringify(expected))return false;
      db=clone(next);writes++;return true;
    }}};
    await run({call:(name,args)=>sandbox.solvitPlugin.tools[name](args,ctx),get:()=>clone(db),
      race:callback=>{interfere=callback},writes:()=>writes});
  } finally {await rm(dir,{recursive:true});}
}

test('page edits replace tags while retaining source and protecting manual modifications',async()=>{
  await fixture(async({call,get})=>{
    const original=get().facts[0];
    assert.equal(await call('save',{expected:original,text:' Uses Neovim ',category:'preference',tags:['new']}),'m1');
    const edited=get().facts[0];assert.equal(edited.text,'Uses Neovim');assert.deepEqual(edited.tags,['new']);
    assert.deepEqual(edited.source,original.source);assert.equal(edited.createdAt,original.createdAt);assert.equal(edited.modifiedByUser,true);
    await call('remove',edited);assert.equal(get().edges.length,0);assert.equal(get().facts.length,1);
    assert.deepEqual(get().completedSources[0].factIds,['m1']);
  });
});

test('CAS retries reject concurrent changes to the edited or deleted record',async()=>{
  for(const name of ['save','remove'])await fixture(async({call,get,race,writes})=>{
    const expected=get().facts[0];race(db=>{db.facts[0].text='Concurrent update'});
    await assert.rejects(()=>call(name,name==='save'?{expected,text:'Draft',category:'fact',tags:[]}:expected),/Memory changed/);
    assert.equal(writes(),0);assert.equal(get().facts[0].text,'Concurrent update');assert.equal(get().edges.length,1);
  });
});

test('CAS retries preserve unrelated writes and explicit creation retains duplicate records',async()=>{
  await fixture(async({call,get,race})=>{
    const expected=get().facts[0];race(db=>{db.profile.city={value:'Shanghai',updatedAt:'now'}});
    await call('save',{expected,text:'Uses Neovim',category:'preference',tags:[]});
    assert.equal(get().profile.city.value,'Shanghai');
    assert.equal(await call('save',{expected:null,text:'Uses Neovim',category:'preference',tags:[]}),'m3');
    assert.equal(get().facts.length,3);assert.equal(get().facts[2].source,undefined);
  });
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,writeFile,readFile,rm } from 'node:fs/promises';
import { join,resolve } from 'node:path';
import { tmpdir } from 'node:os';
import vm from 'node:vm';
import { buildPlugin } from '../scripts/build-plugin.mjs';

async function fixture(facts,run) {
  const dir=await mkdtemp(join(tmpdir(),'solvit-memory-page-'));
  try {
    const entry=join(dir,'entry.ts'),manifest=join(dir,'manifest.json'),output=join(dir,'output.json');
    const module=resolve('../builtin-plugins/memory/pages.ts');
    await writeFile(entry,`import {createMemoryPage} from ${JSON.stringify(module)};
      export default {tools:{},pages:{main:createMemoryPage({read:ctx=>ctx.storage.get('memory.v1'),
        save:async(input,ctx)=>{await ctx.storage.set('mutation',input);return input.expected?.id??'new-id'},
        remove:async(expected,ctx)=>{await ctx.storage.set('removed',expected)}})}};`);
    await writeFile(manifest,JSON.stringify({id:'test.memory-page',name:'Memory',description:'Test',version:'1.0.0',apiVersion:9,
      permissions:['storage'],dependencies:[],tools:[],pages:[{id:'main',title:'Memory'}]}));
    await buildPlugin(entry,manifest,output);
    const sandbox=vm.createContext({}); vm.runInContext(JSON.parse(await readFile(output,'utf8')).source,sandbox);
    const db={format:1,sequence:facts.length,facts,edges:[],chunks:[],profile:{}};
    const writes=new Map();
    const ctx={storage:{get:async()=>structuredClone(db),set:async(key,value)=>{writes.set(key,JSON.parse(JSON.stringify(value)));return null}}};
    const page=(action,fields={},value,locale='zh')=>sandbox.solvitPlugin.pages.main({locale,event:action?{nodeId:action,action,fields,...(value?{value}:{})}:null},ctx);
    await run({page,db,writes});
  } finally {await rm(dir,{recursive:true});}
}
const fact=(id,text='Uses Vim')=>({id,text,category:'preference',tags:['editor'],createdAt:'2026-10-12',updatedAt:'2026-10-12'});
const flatten=view=>{const result=[];const walk=n=>{result.push(n);for(const c of n.children??[])walk(c)};for(const n of [...view.nodes,...view.footer??[]])walk(n);return result};

test('memory page filters real facts by query/category/role and paginates',async()=>{
  const source={turnId:'answer',assistantMessageId:'answer',userMessageId:'question',sessionId:'chat',roleId:'role-a',roleName:'Developer',quote:'I use Vim'};
  await fixture(Array.from({length:25},(_,i)=>({...fact(`m${i}`),...(i===24?{source}:{})})),async({page})=>{
    let view=await page();assert.equal(flatten(view).filter(n=>n.id.startsWith('open_')).length,20);
    view=await page('next');assert.equal(flatten(view).filter(n=>n.id.startsWith('open_')).length,5);
    view=await page('filter',{query:'Vim',category:'preference',role:'Developer'});
    assert.equal(flatten(view).filter(n=>n.id.startsWith('open_')).length,1);
    view=await page('open_0');assert.equal(flatten(view).find(n=>n.id==='quote').text,'I use Vim');
    assert.ok(flatten(view).find(n=>n.id==='remove').confirm);
  });
});

test('edit page retains user input and refuses a changed expected fact before mutation',async()=>{
  await fixture([fact('m1')],async({page,db,writes})=>{
    await page();await page('open_0');await page('edit');
    db.facts[0].text='Changed by background task';
    const view=await page('save',{text:'My draft',edit_category:'preference',tags:'editor'});
    assert.equal(writes.size,0);assert.equal(flatten(view).find(n=>n.id==='text').value,'My draft');
    assert.ok(flatten(view).some(n=>n.text?.includes('已经变化')));
    await page('reload');await page('save',{text:'New manual edit',edit_category:'preference',tags:'editor, personal'});
    assert.equal(writes.get('mutation').expected.text,'Changed by background task');
    assert.deepEqual(writes.get('mutation').tags,['editor','personal']);
  });
});

test('full bounded memory graph uses actual edges and maps native selection to detail',async()=>{
  await fixture(Array.from({length:256},(_,i)=>fact(`m${i}`,'长记忆'.repeat(100))),async({page,db})=>{
    db.edges=Array.from({length:512},(_,i)=>({from:`m${i%256}`,to:`m${(i+1)%256}`,relation:'真实关系'.repeat(20)}));
    await page();const view=await page('graph');const graph=flatten(view).find(n=>n.kind==='graph');
    assert.equal(graph.nodes.length,256);assert.equal(graph.edges.length,512);
    assert.ok(Buffer.byteLength(JSON.stringify(view))<=128*1024);
    const details=await page('select_node',{},'g255');assert.ok(flatten(details).some(n=>n.text==='记录 ID: m255'));
  });
});

test('all six memory page languages and shared profile/document previews are supplied by TS',async()=>{
  await fixture([],async({page,db})=>{
    for(const [locale,title] of [['zh','记忆'],['en','Memory'],['ja','記憶'],['ko','기억'],['es','Memoria'],['fr','Mémoire']])assert.equal((await page(undefined,{},undefined,locale)).title,title);
    db.profile.editor={value:'Vim',updatedAt:'2026-10-12'};db.chunks=[{id:'c1',document:'d1',title:'Project notes',index:0,text:'Actual stored document'}];
    assert.ok(flatten(await page('profiles')).some(n=>n.text==='Vim'));
    await page('back');assert.ok(flatten(await page('documents')).some(n=>n.text==='Actual stored document'));
  });
});

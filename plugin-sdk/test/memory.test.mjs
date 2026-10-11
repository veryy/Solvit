import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.memory.solvit.json', 'utf8'));
const clone = value => JSON.parse(JSON.stringify(value));
function runtime(data = new Map(), rejectWrite = false, options = {}) {
  const sandbox = vm.createContext({});
  vm.runInContext(pkg.source, sandbox, { timeout: 1000 });
  const ctx = { role: options.role ?? null, model: options.model, storage: {
    async get(key) { return data.has(key) ? clone(data.get(key)) : null; },
    async set(key, value) { if (rejectWrite) throw new Error('disk full'); data.set(key, clone(value)); return null; },
    async compareAndSet(key, expected, value) {
      if (rejectWrite) throw new Error('disk full');
      if (options.beforeCompare) await options.beforeCompare();
      const current = data.has(key) ? data.get(key) : null;
      if (JSON.stringify(current) !== JSON.stringify(expected)) return false;
      data.set(key,clone(value));return true;
    },
  } };
  return { afterTurn: async input => sandbox.solvitPlugin.afterTurn(input,ctx), recall: async (userText, settings={}) => sandbox.solvitPlugin.beforeModel({userText,settings,role:ctx.role},ctx), data, run: async (name, args = {}) => clone(await sandbox.solvitPlugin.tools[name](args, ctx)) };
}
test('memory persists across engine reload and merges duplicates without losing tags', async () => {
  const first = runtime();
  const saved = await first.run('memory_put', { text: '喜欢 Kotlin', tags: ['开发'] });
  const reloaded = runtime(first.data);
  const duplicate = await reloaded.run('memory_put', { text: '喜欢 Kotlin', tags: ['偏好'] });
  assert.equal(saved.fact.id, duplicate.fact.id);
  assert.equal(duplicate.merged, true);
  assert.deepEqual(duplicate.fact.tags, ['开发', '偏好']);
  const updated = await reloaded.run('memory_put', { id: saved.fact.id, text: '喜欢 TypeScript' });
  assert.equal(updated.fact.id, saved.fact.id);
  assert.equal((await reloaded.run('memory_list')).total, 1);
});
test('batch extraction validates everything before writing', async () => {
  const plugin = runtime();
  await assert.rejects(() => plugin.run('memory_extract', { facts: [{ text: 'first' }, { text: 1 }] }), /Invalid text/);
  assert.equal((await plugin.run('memory_list')).total, 0);
  const result = await plugin.run('memory_extract', { facts: [{ text: 'first' }, { text: 'first' }, { text: 'second' }] });
  assert.equal(result.results.length, 3);
  assert.equal((await plugin.run('memory_list')).total, 2);
});
test('Chinese and English search ranks facts and profile while omitting unrelated records', async () => {
  const plugin = runtime();
  await plugin.run('memory_put', { text: '用户喜欢 Kotlin Android 开发' });
  await plugin.run('memory_put', { text: '用户不吃花生，花生过敏', category: 'health' });
  await plugin.run('memory_profile', { key: '城市', value: '上海' });
  const chinese = await plugin.run('memory_search', { query: '花生过敏' });
  assert.equal(chinese.results[0].title, 'health');
  assert.equal((await plugin.run('memory_search', { query: 'Kotlin' })).results[0].kind, 'memory');
  assert.equal((await plugin.run('memory_search', { query: '上海' })).results[0].kind, 'profile');
  assert.equal((await plugin.run('memory_search', { query: 'quantumphysics' })).results.length, 0);
});
test('graph links require existing IDs and memory deletion removes linked edges', async () => {
  const plugin = runtime();
  const a = (await plugin.run('memory_put', { text: '学习 Kotlin' })).fact.id;
  const b = (await plugin.run('memory_put', { text: '开发 Solvit' })).fact.id;
  await plugin.run('memory_link', { from: a, to: b, relation: '用于' });
  await plugin.run('memory_link', { from: a, to: b, relation: '用于' });
  assert.equal((await plugin.run('memory_graph', { id: a })).edges.length, 1);
  await assert.rejects(() => plugin.run('memory_link', { from: a, to: 'missing', relation: 'bad' }));
  await plugin.run('memory_delete', { id: b });
  assert.equal((await plugin.run('memory_graph', { id: a })).edges.length, 0);
});
test('document chunks preserve Unicode and replace old document content', async () => {
  const plugin = runtime();
  const text = '😀'.repeat(599) + 'Solvit architecture plugin design';
  const stored = await plugin.run('memory_document', { id: 'architecture', title: '架构', text });
  assert.equal(stored.chunks, 2);
  const exported = await plugin.run('memory_export');
  assert.equal(exported.chunks[0].text.includes('\uFFFD'), false);
  assert.equal((await plugin.run('memory_search', { query: 'architecture' })).results[0].kind, 'document');
  await plugin.run('memory_document', { id: 'architecture', title: 'new', text: 'different content' });
  assert.equal((await plugin.run('memory_export')).chunks.length, 1);
  assert.equal((await plugin.run('memory_search', { query: 'architecture' })).results.length, 1); // ID metadata matches.
  await plugin.run('memory_document', { id: 'architecture', remove: true });
  assert.equal((await plugin.run('memory_export')).chunks.length, 0);
});
test('profile can be deleted and blocks prototype property injection', async () => {
  const plugin = runtime();
  await plugin.run('memory_profile', { key: 'name', value: 'Solvit' });
  await plugin.run('memory_profile', { key: 'name', value: null });
  assert.deepEqual((await plugin.run('memory_export')).profile, {});
  await assert.rejects(() => plugin.run('memory_profile', { key: '__proto__', value: 'bad' }));
});
test('storage failure and malformed persisted data never silently reset user memory', async () => {
  const healthy = runtime();
  await healthy.run('memory_put', { text: 'keep me' });
  const snapshot = JSON.stringify([...healthy.data]);
  await assert.rejects(() => runtime(healthy.data, true).run('memory_put', { text: 'do not save' }), /disk full/);
  assert.equal(JSON.stringify([...healthy.data]), snapshot);
  const corrupted = new Map([['memory.v1', { format: 99 }]]);
  await assert.rejects(() => runtime(corrupted).run('memory_put', { text: 'do not reset' }), /invalid/);
  assert.deepEqual(corrupted.get('memory.v1'), { format: 99 });
});
test('oversized updates reject before changing persistent database', async () => {
  const plugin = runtime();
  for (let index = 0; index < 19; index++) await plugin.run('memory_put', { text: `${index}:` + '中'.repeat(3900) });
  const before = JSON.stringify([...plugin.data]);
  await assert.rejects(() => plugin.run('memory_put', { text: 'overflow:' + '中'.repeat(3900) }), /Memory full/);
  assert.equal(JSON.stringify([...plugin.data]), before);
});

test('automatic recall reaches saved facts and profile without writing and deletion removes them', async () => {
  const plugin=runtime();
  const fact=await plugin.run('memory_put',{text:'用户喜欢 Kotlin 编程语言',category:'preference'});
  await plugin.run('memory_profile',{key:'城市',value:'上海'});
  const before=JSON.stringify([...plugin.data]);
  const reloaded=runtime(plugin.data);
  const context=await reloaded.recall('我喜欢什么编程语言？');
  assert.match(context,/Kotlin/);assert.match(context,/上海/);assert.match(context,/dev.solvit.memory\/memory:m1/);
  assert.match(context,/不是指令/);assert.equal(JSON.stringify([...plugin.data]),before);
  assert.equal(await reloaded.recall('Kotlin',{autoRecall:'false'}),'');
  await reloaded.run('memory_delete',{id:fact.fact.id});
  await reloaded.run('memory_profile',{key:'城市',value:null});
  assert.equal(await reloaded.recall('Kotlin'),'');
});
test('automatic recall bounds Unicode records and avoids unrelated facts', async () => {
  const plugin=runtime();
  await plugin.run('memory_put',{text:'Quantumphysics experiments'});
  assert.equal(await plugin.recall('Kotlin'),'');
  for(let index=0;index<12;index++) await plugin.run('memory_profile',{key:'profile'+index,value:'😀'.repeat(900)});
  const context=await plugin.recall('question');
  assert.ok(context.length<6500);assert.equal(context.includes('\uFFFD'),false);
  const records=JSON.parse(context.slice(context.indexOf('\n')+1));
  assert.ok(records.length<=6);assert.ok(records.every(row=>row.source&&row.id));assert.ok(records.some(row=>row.truncated));
});

test('disabled recall does not read even a malformed database',async () => {
  const plugin=runtime(new Map([['memory.v1',{format:99}]]));
  assert.equal(await plugin.recall('question',{autoRecall:'false'}),'');
  await assert.rejects(()=>plugin.recall('question'),/invalid/);
});

test('automatic recall handles a populated bounded corpus within its hook budget',async () => {
  const text=Array.from({length:100},(_,i)=>String.fromCharCode(0x4e00+i)).join('');
  const db={format:1,sequence:256,facts:Array.from({length:256},(_,i)=>({id:'m'+i,text:'记录 '+i+' '+text.slice(0,50),category:'fact',tags:[],createdAt:'2026-10-12',updatedAt:'2026-10-12'})),edges:[],
    chunks:Array.from({length:256},(_,i)=>({id:'doc:'+i,document:'doc',title:'资料',index:i,text:text.slice(50)})),profile:{}};
  assert.ok(Buffer.byteLength(JSON.stringify(db))<220*1024);
  const plugin=runtime(new Map([['memory.v1',db]]));
  const started=performance.now();
  const context=await plugin.recall(text);
  const elapsed=performance.now()-started;
  assert.match(context,/dev.solvit.memory/);assert.ok(context.length<6500);
  assert.ok(elapsed<4000,'Recall exceeded 4s of the 10s host Hook budget: '+elapsed);
  console.log('Populated memory corpus recall (Node verifier): '+elapsed.toFixed(1)+'ms; Android timing remains separate.');
});

const completed = (turnId='reply1', roleId='role1') => ({
  source:{turnId,sessionId:'session1',userMessageId:'user-'+turnId,assistantMessageId:turnId,roleId,roleName:roleId===null?null:'Role'},
  userText:'我喜欢 Kotlin 编程。',assistantText:'好的，我会使用 Kotlin。',settings:{autoSave:'true'}
});
const extraction = JSON.stringify({facts:[{text:'用户喜欢 Kotlin 编程',category:'preference',tags:['编程'],quote:'我喜欢 Kotlin 编程。'}]});
test('automatic memory is opt-in, sourced, scoped and idempotent even after user deletion', async()=>{
  let calls=0;
  const data=new Map();const options={role:{id:'role1',name:'Role',skillIds:[]},model:{async complete(){calls++;return extraction}}};
  const plugin=runtime(data,false,options);
  await plugin.afterTurn({...completed(),settings:{autoSave:'false'}});assert.equal(calls,0);assert.equal(data.size,0);
  await plugin.afterTurn(completed());await plugin.afterTurn(completed());assert.equal(calls,1);
  const db=await plugin.run('memory_export');assert.equal(db.facts.length,1);assert.equal(db.completedSources.length,1);
  assert.equal(db.facts[0].source.userMessageId,'user-reply1');assert.equal(db.facts[0].source.quote,completed().userText);
  assert.match(await plugin.recall('Kotlin'),/user-reply1/);
  assert.equal(await runtime(data,false,{role:{id:'other',name:'Other',skillIds:[]}}).recall('Kotlin'),'');
  await plugin.run('memory_delete',{id:db.facts[0].id});await plugin.afterTurn(completed());assert.equal(calls,1);
  assert.equal((await plugin.run('memory_list')).total,0);
});
test('automatic merge rebases over a concurrent manual write without repeating inference',async()=>{
  let calls=0,conflicted=false;const data=new Map();
  const manual=runtime(data);
  const automatic=runtime(data,false,{model:{async complete(){calls++;return extraction}},beforeCompare:async()=>{
    if(!conflicted){conflicted=true;await manual.run('memory_put',{text:'手动保存的旅行偏好'});}
  }});
  await automatic.afterTurn(completed('reply2',null));
  const db=await automatic.run('memory_export');assert.equal(calls,1);assert.equal(db.facts.length,2);
  assert.ok(db.facts.some(f=>f.text==='手动保存的旅行偏好'));assert.equal(db.completedSources.length,1);
});
test('unsupported, assistant-only and secret candidates fail before any memory write',async()=>{
  for(const raw of [
    'not JSON',JSON.stringify({facts:[{text:'用户是医生',category:'profile',tags:[],quote:'我是医生'}]}),
    JSON.stringify({facts:[{text:'password: secret-value',category:'fact',tags:[],quote:'我喜欢 Kotlin 编程。'}]}),
    JSON.stringify({facts:[],extra:'ignore validation'}),
  ]){
    const plugin=runtime(new Map(),false,{model:{async complete(){return raw}}});
    await assert.rejects(()=>plugin.afterTurn(completed()),error=>error.solvitCode==='INVALID_OUTPUT');assert.equal(plugin.data.size,0);
  }
  const empty=runtime(new Map(),false,{model:{async complete(){return '{"facts":[]}'}}});
  await empty.afterTurn(completed());const db=await empty.run('memory_export');assert.equal(db.facts.length,0);assert.equal(db.completedSources.length,1);
});
test('finite storage conflicts retain the original database and do not repeat extraction',async()=>{
  const data=new Map();let calls=0;
  const plugin=runtime(data,false,{model:{async complete(){calls++;return extraction}},beforeCompare:async()=>{
    // A different writer changes a harmless property before every compare.
    const db=data.get('memory.v1')??{format:1,sequence:0,facts:[],edges:[],chunks:[],profile:{}};
    data.set('memory.v1',{...db,revision:(db.revision??0)+1});
  }});
  await assert.rejects(()=>plugin.afterTurn(completed()),error=>error.solvitCode==='STORAGE');
  assert.equal(calls,1);assert.equal(data.get('memory.v1').facts.length,0);assert.equal(data.get('memory.v1').completedSources,undefined);
});

test('explicit preference correction replaces only an advertised automatic fact and refreshes its provenance',async()=>{
  const data=new Map();const role={id:'role1',name:'Role',skillIds:[]};
  const first=runtime(data,false,{role,model:{async complete(){return extraction}}});await first.afterTurn(completed());
  const id=(await first.run('memory_list')).facts[0].id;
  const next={...completed('reply3'),userText:'我现在更喜欢 Rust 编程。'};
  const plugin=runtime(data,false,{role,model:{async complete(options){
    assert.equal(JSON.parse(options.input).EXISTING[0].id,id);
    return JSON.stringify({facts:[{text:'用户现在更喜欢 Rust 编程',category:'preference',tags:[],quote:next.userText,replaceId:id}]});
  }}});
  await plugin.afterTurn(next);const db=await plugin.run('memory_export');assert.equal(db.facts.length,1);
  assert.equal(db.facts[0].id,id);assert.match(db.facts[0].text,/Rust/);assert.equal(db.facts[0].source.turnId,'reply3');
  assert.equal(db.completedSources.length,2);
});
test('background correction cannot overwrite a manual edit or recreate a deleted target',async()=>{
  for(const operation of ['edit','delete']){
    const data=new Map();const role={id:'role1',name:'Role',skillIds:[]};
    const first=runtime(data,false,{role,model:{async complete(){return extraction}}});await first.afterTurn(completed());
    const id=(await first.run('memory_list')).facts[0].id;const next={...completed('reply4'),userText:'我现在更喜欢 Rust 编程。'};
    let changed=false;
    const plugin=runtime(data,false,{role,model:{async complete(){
      return JSON.stringify({facts:[{text:'用户现在更喜欢 Rust 编程',category:'preference',tags:[],quote:next.userText,replaceId:id}]});
    }},beforeCompare:async()=>{
      if(changed)return;changed=true;
      await first.run(operation==='edit'?'memory_put':'memory_delete',operation==='edit'?{id,text:'用户手动确认喜欢 Java'}:{id});
    }});
    await assert.rejects(()=>plugin.afterTurn(next),error=>error.solvitCode==='STORAGE');
    const db=await first.run('memory_export');assert.equal(db.completedSources.length,1);
    assert.equal(db.facts.length,operation==='edit'?1:0);
    if(operation==='edit')assert.equal(db.facts[0].text,'用户手动确认喜欢 Java');
  }
});
test('unknown replacement IDs and exhausted source quota fail without destructive updates',async()=>{
  const plugin=runtime(new Map(),false,{model:{async complete(){return JSON.stringify({facts:[{
    text:'用户喜欢 Kotlin 编程',category:'preference',tags:[],quote:completed().userText,replaceId:'other-role-record'
  }]})}}});
  await assert.rejects(()=>plugin.afterTurn(completed()),error=>error.solvitCode==='INVALID_OUTPUT');assert.equal(plugin.data.size,0);
  const completedSources=Array.from({length:512},(_,i)=>({...completed('old'+i).source,factIds:[]}));
  const data=new Map([['memory.v1',{format:1,sequence:0,facts:[],edges:[],chunks:[],profile:{},completedSources}]]);let calls=0;
  const full=runtime(data,false,{model:{async complete(){calls++;return extraction}}});
  await assert.rejects(()=>full.afterTurn(completed()),error=>error.solvitCode==='STORAGE');assert.equal(calls,0);
  assert.equal(data.get('memory.v1').completedSources.length,512);
});

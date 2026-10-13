import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {tmpdir} from 'node:os';
import vm from 'node:vm';
import {buildPlugin} from '../scripts/build-plugin.mjs';

test('installable TS Agent requests individual model and tool steps without a native loop proxy',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'solvit-agent-'));
  try {
    const output=join(dir,'agent.json');
    await buildPlugin(resolve('../plugin-examples/agent/index.ts'),resolve('../plugin-examples/agent/manifest.json'),output);
    const pkg=JSON.parse(await readFile(output,'utf8'));
    assert.equal(pkg.manifest.apiVersion,14);
    assert.equal(pkg.manifest.settings[0].defaultValue,'false');
    const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
    const ops=[];
    const context=Object.fromEntries(['model','tool','settle','finish'].map(op=>[op,async index=>{
      ops.push([op,index]);return op==='model'&&index===0?2:0;
    }]));
    await sandbox.solvitPlugin.agent({maxRounds:8},context);
    assert.deepEqual(ops,[['model',0],['tool',0],['tool',1],['settle',0],['model',1],['finish',1]]);
  }finally{await rm(dir,{recursive:true});}
});

test('Agent package declarations reject old APIs, missing grants and default-on strategies',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'solvit-agent-contract-'));
  try {
    const base=JSON.parse(await readFile(resolve('../plugin-examples/agent/manifest.json'),'utf8'));
    const file=join(dir,'manifest.json');
    for(const manifest of [{...base,apiVersion:13},{...base,permissions:[]},
      {...base,agentEnabledSetting:undefined},{...base,settings:[{...base.settings[0],defaultValue:'true'}]}]){
      await writeFile(file,JSON.stringify(manifest));
      await assert.rejects(()=>buildPlugin(resolve('../plugin-examples/agent/index.ts'),file,join(dir,'output.json')));
    }
  }finally{await rm(dir,{recursive:true});}
});

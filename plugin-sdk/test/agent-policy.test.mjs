import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('packaged observation policy declines truncated arguments and checks actual settlement',async()=>{
  const pkg=JSON.parse(await readFile('../market-server/packages/example.agent-policy.solvit.json','utf8'));
  assert.equal(pkg.manifest.apiVersion,15);
  assert.ok(pkg.manifest.permissions.includes('agent.read'));
  const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox);
  const events=[];let stage=null;
  await sandbox.solvitPlugin.agent({maxRounds:8},{
    model:async round=>{events.push('model:'+round);return round===0?1:0;},
    observe:async()=>({tools:[{index:0,arguments:{text:'incomplete',truncated:true},stage}]}),
    decline:async index=>{events.push('decline:'+index);stage='DENIED';},
    tool:async()=>{throw new Error('Unreviewable arguments executed');},
    settle:async round=>events.push('settle:'+round),
    finish:async round=>events.push('finish:'+round),
  });
  assert.deepEqual(events,['model:0','decline:0','settle:0','model:1','finish:1']);
});

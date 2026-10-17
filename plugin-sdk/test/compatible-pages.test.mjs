import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
for (const [id,hash] of [
 ['skills','83a8912346d3e120f8795a5f35a7ed41a97d90005706305031f3fe34e68bbebf'],
 ['mcp','1d437a9297e6d570ce34d8c306e2acae02d0b899a94f3d058f27835073a23a66']
]) test(`${id} UI-only update keeps historical execution and authorization contracts`,async()=>{
 const load=path=>readFile(new URL(path,import.meta.url));
 const originalBytes=await load(`../../market-server/packages/legacy/dev.solvit.${id}-1.1.1.solvit.json`);
 assert.equal(createHash('sha256').update(originalBytes).digest('hex'),hash);
 const original=JSON.parse(originalBytes);
 const updatedBytes=await load(`../../market-server/packages/legacy/dev.solvit.${id}-1.1.2.solvit.json`);
 const updated=JSON.parse(updatedBytes);
 assert.equal(updated.source,original.source);
 assert.deepEqual(updated.manifest,{...original.manifest,version:'1.1.2'});
 const current=JSON.parse(await load(`../../market-server/packages/dev.solvit.${id}.solvit.json`));
 assert.deepEqual(updated.resources,current.resources);
 assert.deepEqual(updatedBytes,await load(`../../plugin-market/src/main/assets/builtin-compat/${id}-1.1.2.solvit.json`));
});

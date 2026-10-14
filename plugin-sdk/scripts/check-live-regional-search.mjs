import {readFile,writeFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const require=createRequire(process.env.SOLVIT_BROWSER_PACKAGE+'/package.json');
const {chromium,devices}=require('playwright');
const pkg=JSON.parse(await readFile(new URL('../../market-server/packages/dev.solvit.web-search.solvit.json',import.meta.url),'utf8'));
const kotlin=await readFile(new URL('../../web-android/src/main/kotlin/dev/solvit/web/PageObservation.kt',import.meta.url),'utf8');
const script=/val script = """([\s\S]*?)"""\.trimIndent\(\)/.exec(kotlin)?.[1];
assert.ok(script);
const sandbox=vm.createContext({});vm.runInContext(pkg.source,sandbox,{timeout:1000});
const browser=await chromium.launch({headless:true});
let injected=0,result,source;
const observations=[];
const ctx={settings:{get:async()=>null},http:{request:async()=>{throw new Error('This acceptance requires the live regional DOM, not RSS')}},web:{visit:async url=>{
  if(url.startsWith('https://www.bing.com/search?')){injected++;throw new Error('Controlled primary-origin outage')}
  const context=await browser.newContext({...devices['Pixel 7'],locale:'zh-CN'});
  const page=await context.newPage();
  await page.route('**/*',route=>{
    const headers={...route.request().headers()};delete headers.cookie;
    return route.request().url().startsWith('https://')?route.continue({headers}):route.abort();
  });
  try{
    const visitStarted=Date.now();
    await page.goto(url,{waitUntil:'load',timeout:7000});await page.waitForTimeout(500);
    let snapshot=JSON.parse(await page.evaluate(script));
    let bodyObservations=1;
    const bodyDeadline=Math.min(visitStarted+7000,Date.now()+3000);
    while(!snapshot.text.trim()&&Date.now()<bodyDeadline){
      await page.waitForTimeout(250);snapshot=JSON.parse(await page.evaluate(script));bodyObservations++;
    }
    observations.push({requested:url,final:snapshot.url,title:snapshot.title,bodyObservations,searchResults:snapshot.searchResults,links:snapshot.links.length,characters:snapshot.text.length,
      dom:await page.evaluate(()=>({readyState:document.readyState,bodyText:document.body.innerText.slice(0,2000),bodyResults:document.querySelectorAll('li.b_algo h2 a[href],a.result__a[href]').length,main:document.querySelector('main,article,[role="main"]')?.outerHTML.slice(0,2000)}))});
    if(!snapshot.text.trim())throw new Error('Page has no readable body after bounded observation');
    return snapshot;
  }catch(error){
    observations.push({requested:url,final:page.url(),observationFailed:true,failureType:error?.name??'Error'});
    throw error;
  }finally{await context.close()}
}}};
try{
  result=await sandbox.solvitPlugin.tools.search({query:'Kotlin programming language official documentation',limit:5},ctx);
  assert.equal(injected,1);assert.equal(result.provider,'bing-browser');assert.ok(result.results?.length);
  assert.ok(observations.some(x=>x.requested.startsWith('https://cn.bing.com/search?')&&x.searchResults===true));
  source=await sandbox.solvitPlugin.tools.read_page({url:result.results[0].url},ctx);
  assert.ok(!source.error&&source.text?.length>=100&&/kotlin/i.test(source.text));
  console.log('Controlled primary outage: real regional browser results and source body passed. This does not guarantee external service availability.');
}finally{
  await writeFile('browser-regional-evidence.json',JSON.stringify({injectedPrimaryFailure:true,injected,result,source:source?{url:source.url,title:source.title,characters:source.text?.length,error:source.error}:null,observations},null,2));
  await browser.close();
}

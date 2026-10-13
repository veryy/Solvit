import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import vm from 'node:vm';
const require = createRequire(process.env.SOLVIT_BROWSER_PACKAGE + '/package.json');
const { chromium } = require('playwright');
const pkg = JSON.parse(await readFile(new URL('../../market-server/packages/dev.solvit.web-search.solvit.json', import.meta.url), 'utf8'));
const kotlin = await readFile(new URL('../../web-android/src/main/kotlin/dev/solvit/web/PageObservation.kt', import.meta.url), 'utf8');
const script = /val script = """([\s\S]*?)"""\.trimIndent\(\)/.exec(kotlin)?.[1];
if (!script || pkg.manifest.apiVersion !== 6) throw new Error('Expected API 6 search and actual Android DOM observer');
const sandbox = vm.createContext({}); vm.runInContext(pkg.source, sandbox, {timeout:1000});
const browser = await chromium.launch({headless:true});
const context = await browser.newContext({locale:'zh-CN'});
const observations = [], checks = [];
const ctx = {settings:{get:async()=>null},http:{request:async options=>{
  const response=await fetch(options.url,{headers:options.headers,signal:AbortSignal.timeout(options.timeoutMs??6000)});
  return {status:response.status,headers:Object.fromEntries(response.headers),body:await response.text()};
}},web:{visit:async url=>{
  const page=await context.newPage();
  await page.route('**/*',route=>route.request().url().startsWith('https://') ? route.continue() : route.abort());
  try {
    await page.goto(url,{waitUntil:'load',timeout:7000});
    await page.waitForTimeout(500);
    const snapshot=JSON.parse(await page.evaluate(script));
    observations.push({url,finalUrl:snapshot.url,title:snapshot.title,links:snapshot.links.length,textSample:snapshot.text.slice(0,2000),dom:await page.evaluate(()=>({bodyText:document.body.innerText.slice(0,2000),bodyLinks:document.querySelectorAll('a[href]').length,results:document.querySelectorAll('li.b_algo').length,main:document.querySelector('main,article,[role="main"]')?.outerHTML.slice(0,2000)}))});
    return snapshot;
  } catch (error) {
    observations.push({url,finalUrl:page.url(),observationFailed:true,failureType:error?.name??'Error'});
    throw error;
  } finally {await page.close();}
}}};
try {
  let latestVersion,releaseFailure;
  try {
    const releaseResponse=await fetch('https://api.github.com/repos/JetBrains/kotlin/releases/latest',{signal:AbortSignal.timeout(10000)});
    if (!releaseResponse.ok) throw new Error('Official latest-release metadata unavailable');
    const release=await releaseResponse.json();
    latestVersion=release.tag_name?.replace(/^v/,'');
    if (!/^\d+\.\d+\.\d+$/.test(latestVersion)||release.prerelease||release.draft) throw new Error('Expected official stable release');
  } catch (error) { releaseFailure=error.message; }
  let failures=0;
  for (const item of [
    {query:'Kotlin programming language official documentation',host:'kotlinlang.org',term:/kotlin/i},
    {query:'Python 官方文档 编程语言',host:'python.org',term:/python/i},
    {query:latestVersion?'Kotlin latest stable release '+latestVersion:'Kotlin latest stable release',host:'kotlinlang.org',term:/kotlin/i,expectedVersion:latestVersion,requiresRelease:true},
  ]) {
    const record={query:item.query,expectedVersion:item.expectedVersion,passed:false}; checks.push(record);
    try {
    if (item.requiresRelease && releaseFailure) throw new Error(releaseFailure);
    const result=await sandbox.solvitPlugin.tools.search({query:item.query,limit:5},ctx);
    Object.assign(record,{provider:result.provider,results:result.results??[],error:result.error});
    if (result.error) throw new Error('Search unavailable: '+item.query);
    const relevant=result.results.find(source=>{
      const host=new URL(source.url).hostname;
      return (host===item.host||host.endsWith('.'+item.host))&&item.term.test(source.title+' '+source.snippet);
    });
    if (!relevant) throw new Error('Top five sources lack the expected official source: '+item.query);
    const page=await sandbox.solvitPlugin.tools.read_page({url:relevant.url},ctx);
    record.page={url:page.url,title:page.title,characters:page.text?.length??0,truncated:page.truncated,error:page.error};
    if (page.error||page.text?.length<100||!item.term.test(page.text)) throw new Error('Official source body unavailable: '+relevant.url);
    if (item.expectedVersion && !page.text.includes(item.expectedVersion)) throw new Error('Source does not contain the verified current stable version: '+item.expectedVersion);
    record.passed=true;
    } catch (error) { failures++;record.failure=error.message; }
  }
  if (failures) throw new Error(`${failures}/${checks.length} public search quality cases failed; inspect every recorded case`);
  console.log('Public search quality: bilingual/release queries return relevant official sources and readable bodies. Model answer attribution remains unverified.');
} finally {
  await writeFile('browser-search-evidence.json',JSON.stringify({checks,observations},null,2));
  await context.close(); await browser.close();
}

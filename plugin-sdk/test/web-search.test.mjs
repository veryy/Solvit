import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
async function plugin(settings = {}, request = async () => { throw new Error('unexpected network'); }) {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.web-search.solvit.json','utf8'));
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source,sandbox);
  return { tools:sandbox.solvitPlugin.tools, ctx:{settings:{get:async key => settings[key] ?? null},http:{request}} };
}
test('default public search needs no settings and never forwards stored keys',async () => {
  const {tools,ctx}=await plugin({apiKey:'PRIVATE'},async options => {
    assert.equal(options.headers.Authorization,undefined);
    assert.match(options.url,/^https:\/\/cn\.bing\.com\/search\?format=rss&q=a%20%26%20b$/);
    return {status:200,headers:{},body:'<rss><channel><item><title>A &amp; B</title><link>https://example.com/a?x=1&amp;y=2</link><description>&lt;b&gt;Useful&lt;/b&gt;</description></item><item><title>Unsafe</title><link>javascript:alert(1)</link></item></channel></rss>'};
  });
  const result=await tools.search({query:'a & b'},ctx);
  assert.equal(result.provider,'bing'); assert.equal(result.results.length,1);
  assert.equal(result.results[0].title,'A & B'); assert.equal(result.results[0].snippet,'Useful');
  assert.equal(result.results[0].url,'https://example.com/a?x=1&y=2');
});
test('public search retries the second origin and distinguishes blocking from results',async () => {
  let calls=0;
  const {tools,ctx}=await plugin({},async () => { calls++; return {status:200,headers:{},body:'<html>captcha</html>'}; });
  const result=await tools.search({query:'test'},ctx);
  assert.equal(calls,2); assert.equal(result.results.length,0); assert.match(result.error,/不可用/);
});
test('Tavily and Zhipu use the configured secret and return bounded attributable results',async () => {
  for (const provider of ['tavily','zhipu']) {
    const {tools,ctx}=await plugin({provider,apiKey:'PRIVATE'},async options => {
      assert.equal(options.headers.Authorization,'Bearer PRIVATE'); assert.equal(options.method,'POST');
      const body=JSON.parse(options.body); assert.equal(body.query ?? body.search_query,'Solvit');
      return {status:200,headers:{},body:JSON.stringify({results:[{title:'Source',url:'https://example.com/a',content:'<b>useful</b>'},{url:'javascript:alert(1)'}]})};
    });
    const result=await tools.search({query:'Solvit',limit:1},ctx);
    assert.equal(result.results.length,1); assert.equal(result.results[0].url,'https://example.com/a');
    assert.equal(JSON.stringify(result).includes('PRIVATE'),false);
  }
});
test('SearXNG encodes the query and diagnoses disabled JSON',async () => {
  const {tools,ctx}=await plugin({provider:'searxng',endpoint:'https://search.example/search'},async options => {
    assert.match(options.url,/q=a%20%26%20b&format=json$/); return {status:403,headers:{},body:'private error'};
  });
  const result=await tools.search({query:'a & b'},ctx); assert.equal(result.status,403); assert.equal(JSON.stringify(result).includes('private error'),false);
});
test('page reading strips scripts and never forwards search credentials',async () => {
  const {tools,ctx}=await plugin({apiKey:'PRIVATE'},async options => {
    assert.equal(options.headers.Authorization,undefined);
    return {status:200,headers:{'Content-Type':'text/html'},body:'<html><script>SECRET_SCRIPT</script><p>useful text</p></html>'};
  });
  const result=await tools.read_page({url:'https://example.com'},ctx); assert.equal(result.text,'useful text');
  assert.match((await tools.read_page({url:'http://example.com'},ctx)).error,/HTTPS/);
});

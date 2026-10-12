import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
async function plugin(settings = {}, request = async () => { throw new Error('unexpected network'); }, visit) {
  const pkg = JSON.parse(await readFile('../market-server/packages/dev.solvit.web-search.solvit.json','utf8'));
  const sandbox = vm.createContext({}); vm.runInContext(pkg.source,sandbox);
  return { tools:sandbox.solvitPlugin.tools, ctx:{settings:{get:async key => settings[key] ?? null},http:{request},...(visit ? {web:{visit}} : {})} };
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

test('browser search unwraps engine redirects, bounds and deduplicates safe sources without secrets',async () => {
  const target='https://example.com/中文?q=1';
  const encoded=Buffer.from(target).toString('base64url');
  const {tools,ctx}=await plugin({apiKey:'PRIVATE'},undefined,async (...args) => {
    assert.equal(args.length,1); assert.equal(args[0],'https://cn.bing.com/search?q=Solvit');
    return {url:args[0],title:'Search',text:'',truncated:false,links:[
      {url:'https://cn.bing.com/settings',title:'Settings',snippet:''},
      {url:'https://www.bing.com/ck/a?u=a1'+encoded,title:'Solvit 中文',snippet:'Useful source'},
      {url:target+'#section',title:'Duplicate',snippet:''},
      {url:'https://user:password@example.com/private',title:'Credentials',snippet:''},
      {url:'javascript:alert(1)',title:'Unsafe',snippet:''},
      {url:'https://example.org/extra',title:'Another source',snippet:'Details'},
    ]};
  });
  const result=await tools.search({query:'Solvit',limit:1},ctx);
  assert.equal(result.provider,'bing-browser'); assert.equal(result.results.length,1);
  assert.equal(result.results[0].url,target); assert.equal(result.results[0].snippet,'Useful source');
  assert.equal(JSON.stringify(result).includes('PRIVATE'),false);
});
test('browser denial falls back to DuckDuckGo and unwraps its source URL',async () => {
  const visits=[];
  const {tools,ctx}=await plugin({},undefined,async url => {
    visits.push(url); if(!url.startsWith('https://html.duckduckgo.com/')) throw new Error('blocked');
    return {url,title:'Search',text:'',truncated:false,links:[
      {url:'https://duckduckgo.com/l/?uddg='+encodeURIComponent('https://example.com/guide'),title:'中文 question guide',snippet:'Guide body'},
    ]};
  });
  const result=await tools.search({query:'中文 question'},ctx);
  assert.equal(visits.length,3); assert.match(visits[2],/^https:\/\/html\.duckduckgo\.com\/html\/\?q=/);
  assert.equal(result.provider,'duckduckgo-browser'); assert.equal(result.results[0].url,'https://example.com/guide');
});
test('RSS fallback rejects unrelated results and has a bounded HTTP timeout',async () => {
  let calls=0;
  const {tools,ctx}=await plugin({},async options => {
    calls++; assert.equal(options.timeoutMs,6000);
    return {status:200,headers:{},body:'<rss><channel><item><title>Plants and zombies</title><link>https://example.com/games</link><description>Games</description></item></channel></rss>'};
  },async () => {throw new Error('blocked');});
  const result=await tools.search({query:'Solvit Android agent'},ctx);
  assert.equal(calls,1); assert.equal(result.results.length,0); assert.match(result.error,/不可用/);
});
test('page reading preserves browser-observed dynamic text and final source URL',async () => {
  const {tools,ctx}=await plugin({apiKey:'PRIVATE'},undefined,async (...args) => {
    assert.equal(args.length,1);
    return {url:'https://example.com/final',title:'Article',text:'Dynamically rendered article',truncated:true,
      links:[{url:'https://example.com/source',title:'Source',snippet:'Evidence'}]};
  });
  const result=await tools.read_page({url:'https://example.com/start'},ctx);
  assert.equal(result.url,'https://example.com/final'); assert.equal(result.text,'Dynamically rendered article');
  assert.equal(result.truncated,true); assert.equal(result.links[0].url,'https://example.com/source');
});

test('a redirected engine home page is never treated as search results',async () => {
  let visits=0;
  const {tools,ctx}=await plugin({},async () => ({status:503,headers:{},body:''}),async () => {
    visits++;
    return {url:'https://www.bing.com/?q=Solvit',title:'Home',text:'',truncated:false,
      links:[{url:'https://example.com/privacy',title:'Solvit privacy',snippet:'Footer'}]};
  });
  const result=await tools.search({query:'Solvit'},ctx);
  assert.equal(visits,2); assert.equal(result.results.length,0); assert.ok(result.error);
});

test('regional browser failure still obtains real result data from the alternate Bing origin',async () => {
  const visits=[];
  const {tools,ctx}=await plugin({apiKey:'PRIVATE'},undefined,async (...args)=>{
    assert.equal(args.length,1);const url=args[0];visits.push(url);
    if(url.startsWith('https://cn.bing.com/'))throw new Error('Public origin unavailable');
    return {url,title:'Search',text:'',truncated:false,links:[
      {url:'https://example.com/kotlin-guide',title:'Kotlin official guide',snippet:'Kotlin documentation'},
    ]};
  });
  const result=await tools.search({query:'Kotlin'},ctx);
  assert.deepEqual(visits,['https://cn.bing.com/search?q=Kotlin','https://www.bing.com/search?q=Kotlin']);
  assert.equal(result.provider,'bing-browser');assert.equal(result.results[0].url,'https://example.com/kotlin-guide');
  assert.equal(JSON.stringify(result).includes('PRIVATE'),false);
});

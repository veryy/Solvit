import cytoscape from 'cytoscape';

const $ = id => document.getElementById(id);
let state, signature='', revision='', fields={}, cy, pendingToken=null, pendingBusy=false;
const isBusy=()=>!!state?.busy||pendingToken!==null;
function updateDisabled(){
  const nodes=state?flatten(state.view):[];
  document.querySelectorAll('button,input,textarea,select').forEach(element=>{
    const node=nodes.find(n=>n.id===element.dataset.node);element.disabled=isBusy()||node?.enabled===false;
  });
}
const labels = {zh:['筛选','关系图已为空','页面操作未完成'],en:['Filters','No relations yet','Page action failed'],ja:['絞り込み','関係がありません','操作に失敗しました'],ko:['필터','관계가 없습니다','작업 실패'],es:['Filtros','Sin relaciones','Acción fallida'],fr:['Filtres','Aucune relation','Action impossible']};
const words = () => labels[state?.locale?.split('-')[0]] ?? labels.zh;
const flatten = view => {const result=[];const walk=node=>{result.push(node);(node.children??[]).forEach(walk)};[...view.nodes,...view.footer??[]].forEach(walk);return result};
function syncGraphCoordinates() {
  if(!cy)return;
  for(const button of document.querySelectorAll('[data-memory-id]')) {
    const node=cy.getElementById(button.dataset.memoryId);if(node.empty())continue;
    const position=node.renderedPosition();button.dataset.x=String(position.x);button.dataset.y=String(position.y);
  }
}
function send(id,value) {
  if (!state || isBusy()) return;
  const node=flatten(state.view).find(n=>n.id===id);
  if (!node || node.enabled===false) return;
  $('menu').hidden=true;document.activeElement?.blur();
  pendingToken=state.token;pendingBusy=false;updateDisabled();
  solvitHost.postMessage(JSON.stringify({type:'event',token:state.token,nodeId:id,fields,...(value!==undefined?{value}:{})}));
}
function action(node) {
  const element=document.createElement('button');element.textContent=node.text;
  element.dataset.node=node.id;element.disabled=isBusy()||node.enabled===false;
  element.onclick=()=>send(node.id);return element;
}
function render(node) {
  if(node.kind==='button')return action(node);
  if(node.kind==='field'||node.kind==='choice') {
    const label=document.createElement('label');label.textContent=node.label;
    const input=document.createElement(node.kind==='choice'?'select':node.multiline?'textarea':'input');
    input.dataset.field=node.id;input.id=`field-${node.id}`;
    if(node.kind==='choice')for(const option of node.options){const e=document.createElement('option');e.value=option.value;e.textContent=option.label;input.append(e)}
    input.value=fields[node.id]??node.value;input.disabled=isBusy();
    input.oninput=()=>{fields[node.id]=input.value};
    if(node.kind==='choice')input.onchange=()=>send(node.id,input.value);
    label.append(input);return label;
  }
  const element=document.createElement(node.kind==='text'?'p':'div');element.dataset.node=node.id;
  if(node.kind==='text'){element.textContent=node.text;element.className=node.tone??'body'}
  else {element.className=node.kind;(node.children??[]).forEach(child=>element.append(render(child)))}
  return element;
}
function fitGraph() {
  if(!cy || cy.nodes().empty())return;
  cy.fit(undefined,60);
  // Fit a large graph down, but do not magnify a small graph's labels.
  if(cy.zoom()>1)cy.zoom({level:1,renderedPosition:{x:cy.width()/2,y:cy.height()/2}});
}
function graph(graphNode,theme) {
  if(!cy){cy=cytoscape({container:$('graph'),elements:[],minZoom:.15,maxZoom:4,layout:{name:'preset'},
    style:[],wheelSensitivity:.25});
    cy.on('tap','node',event=>send('relations',event.target.id()));
    cy.on('tap','edge',event=>{const e=event.target;const p=e.renderedMidpoint();cy.elements().unselect();e.select();
      $('status').textContent=e.data('label');$('status').hidden=false;});
    cy.on('tap',event=>{if(event.target===cy){$('status').hidden=true;$('menu').hidden=true}});
    cy.on('render viewport layoutstop',()=>{$('graph').dataset.zoom=String(cy.zoom());syncGraphCoordinates()});
  }
  cy.style([{selector:'node',style:{'background-color':theme.primary,'label':'data(label)','color':theme.onSurface,
    'font-size':11,'text-wrap':'wrap','text-max-width':100,'text-valign':'bottom','text-margin-y':10,'width':20,'height':20}},
    {selector:'edge',style:{'line-color':theme.outline,'target-arrow-color':theme.outline,'target-arrow-shape':'triangle','curve-style':'bezier','width':1.2}},
    {selector:'edge:selected',style:{'line-color':theme.primary,'width':3}},
    {selector:'node:selected',style:{'border-color':theme.primary,'border-width':2}}]);
  if(graphNode){
    const next=JSON.stringify([graphNode.nodes,graphNode.edges]);
    if(next!==signature){
      const positions=new Map(cy.nodes().map(n=>[n.id(),n.position()]));
      const pan=cy.pan(),zoom=cy.zoom(),first=!signature||cy.nodes().empty();signature=next;
      cy.batch(()=>{
        const elements=[...graphNode.nodes.map(n=>({data:{id:n.id,label:n.label},...(positions.has(n.id)?{position:positions.get(n.id)}:{})})),
          ...graphNode.edges.map((e,i)=>({data:{id:`edge-${i}`,source:e.from,target:e.to,label:e.label}}))];
        cy.elements().remove();cy.add(elements);
      });
      if(first || graphNode.nodes.some(n=>!positions.has(n.id)))cy.layout({name:'cose',nodeDimensionsIncludeLabels:true,animate:false,randomize:first,numIter:250,fit:false,padding:60}).run();
      if(first)fitGraph();else{cy.pan(pan);cy.zoom(zoom)}
    }
  }
  cy.resize();
}
window.solvitPageUpdate = next => {
  if(pendingToken!==null){
    if(next.busy)pendingBusy=true;
    if(next.token!==pendingToken||pendingBusy&&!next.busy)pendingToken=null;
  }
  const changed=revision!==next.token;state=next;
  for(const [key,value]of Object.entries(next.theme))document.documentElement.style.setProperty(`--${key}`,value);
  const nodes=flatten(next.view),relation=nodes.find(n=>n.id==='relations'),home=nodes.some(n=>n.id==='graph_count');
  graph(relation,next.theme);$('sheet').hidden=home;
  $('toolbar').hidden=!home;$('fit').hidden=!home;$('count').hidden=!home;
  $('empty').hidden=!home||!!relation?.nodes.length;$('empty').textContent=nodes.find(n=>n.id==='empty')?.text??words()[1];
  $('count').textContent=nodes.find(n=>n.id==='graph_count')?.text??'';
  if(changed){
    revision=next.token;fields=Object.fromEntries(nodes.filter(n=>n.kind==='field').map(n=>[n.id,n.value]));
    $('query').placeholder=nodes.find(n=>n.id==='query')?.label??'';$('query').value=fields.query??'';
    $('menu').replaceChildren();
    if(home){
      const filters=document.createElement('details'),summary=document.createElement('summary');summary.textContent=words()[0];filters.append(summary);
      for(const id of ['category_filter','role']){const node=nodes.find(n=>n.id===id);if(node)filters.append(render(node))}
      const apply=nodes.find(n=>n.id==='filter');if(apply)filters.append(action(apply));$('menu').append(filters);
      for(const node of next.view.footer??[])if(node.id!=='new')$('menu').append(action(node));
      const clear=nodes.find(n=>n.id==='clear');if(clear)$('menu').append(action(clear));
      if(relation){
        const list=document.createElement('details'),summary=document.createElement('summary');summary.textContent=next.view.title;list.append(summary);
        for(const node of relation.nodes){const button=document.createElement('button');button.textContent=node.label;
          button.dataset.memoryId=node.id;const position=cy.getElementById(node.id).renderedPosition();
          button.dataset.x=String(position.x);button.dataset.y=String(position.y);
          button.onclick=()=>send('relations',node.id);list.append(button)}
        $('menu').append(list);
      }
    }else{
      $('sheet-content').replaceChildren(...next.view.nodes.map(render));
      $('sheet-actions').replaceChildren(...(next.view.footer??[]).map(action));
      $('sheet-content').scrollTop=0;
    }
    const create=nodes.find(n=>n.id==='new');$('new').hidden=!home||!create;$('new').title=create?.text??'';
  }
  updateDisabled();
  window.memoryPageReady=true;
};
$('search').onsubmit=event=>{event.preventDefault();fields.query=$('query').value;send('filter')};
$('query').oninput=()=>{fields.query=$('query').value};
$('options').onclick=()=>{$('menu').hidden=!$('menu').hidden};
$('fit').onclick=fitGraph;$('new').onclick=()=>send('new');
solvitHost.onmessage=event=>{
  const message=JSON.parse(event.data);
  if(message.type==='confirmation'&&message.token!==pendingToken)return;
  pendingToken=null;updateDisabled();
  if(message.type==='error'){$('status').textContent=words()[2];$('status').hidden=false}
};
window.addEventListener('pagehide',()=>cy?.destroy(),{once:true});
solvitHost.postMessage(JSON.stringify({type:'ready'}));

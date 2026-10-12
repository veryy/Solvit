/** Optional HTML renderer shipped inside the plugin bundle, never native app business UI. */
export function mountPage(root,footer,{passwordFields=[]}={}) {
  let state,token='',fields={};
  const flatten=view=>{const out=[];const walk=node=>{out.push(node);(node.children??[]).forEach(walk)};[...view.nodes,...view.footer??[]].forEach(walk);return out};
  const send=(node,value)=>{
    if(!state||state.busy||node.enabled===false)return;
    document.activeElement?.blur();
    solvitHost.postMessage(JSON.stringify({type:'event',token:state.token,nodeId:node.id,fields,...(value===undefined?{}:{value})}));
  };
  const render=node=>{
    if(node.kind==='field'||node.kind==='choice'){
      const label=document.createElement('label');label.textContent=node.label;
      const input=document.createElement(node.kind==='choice'?'select':node.multiline?'textarea':'input');
      input.dataset.field=node.id;input.id=`field-${node.id}`;
      input.autocomplete='off';if(passwordFields.includes(node.id))input.type='password';
      if(node.kind==='choice')for(const option of node.options){const e=document.createElement('option');e.textContent=option.label;e.value=option.value;input.append(e)}
      input.value=fields[node.id]??node.value;input.disabled=state.busy;
      input.oninput=()=>{if(node.kind==='field')fields[node.id]=input.value};
      if(node.kind==='choice')input.onchange=()=>send(node,input.value);
      label.append(input);return label;
    }
    const element=document.createElement(node.kind==='button'?'button':node.kind==='text'?'p':'div');
    element.dataset.node=node.id;
    if(node.kind==='text'){element.textContent=node.text;element.className=node.tone??''}
    else if(node.kind==='button'){element.textContent=node.text;element.disabled=state.busy||node.enabled===false;element.onclick=()=>send(node)}
    else{element.className=node.kind;for(const child of node.children??[])element.append(render(child))}
    return element;
  };
  window.solvitPageUpdate=next=>{
    state=next;
    for(const [key,value]of Object.entries(next.theme))document.documentElement.style.setProperty(`--${key}`,value);
    if(token!==next.token){token=next.token;fields=Object.fromEntries(flatten(next.view).filter(n=>n.kind==='field').map(n=>[n.id,n.value]));
      root.replaceChildren(...next.view.nodes.map(render));footer.replaceChildren(...(next.view.footer??[]).map(render));root.scrollTop=0;
    }
    const nodes=flatten(next.view);
    document.querySelectorAll('button,input,textarea,select').forEach(element=>{const node=nodes.find(n=>n.id===element.dataset.node);element.disabled=next.busy||node?.enabled===false});
    window.pluginPageReady=true;
  };
  solvitHost.postMessage(JSON.stringify({type:'ready'}));
}

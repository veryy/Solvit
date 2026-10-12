/** Optional HTML renderer shipped inside the plugin bundle, never native app business UI. */
export function mountPage(root,footer,{passwordFields=[]}={}) {
  let state,token='',fields={},pendingToken=null,pendingBusy=false;
  const isBusy=()=>!!state?.busy||pendingToken!==null;
  const disable=()=>{
    const nodes=state?flatten(state.view):[];
    document.querySelectorAll('button,input,textarea,select').forEach(element=>{const node=nodes.find(n=>n.id===element.dataset.node);element.disabled=isBusy()||node?.enabled===false});
  };
  const flatten=view=>{const out=[];const walk=node=>{out.push(node);(node.children??[]).forEach(walk)};[...view.nodes,...view.footer??[]].forEach(walk);return out};
  const send=(node,value)=>{
    if(!state||isBusy()||node.enabled===false)return;
    document.activeElement?.blur();
    pendingToken=state.token;pendingBusy=false;disable();
    solvitHost.postMessage(JSON.stringify({type:'event',token:state.token,nodeId:node.id,fields,...(value===undefined?{}:{value})}));
  };
  const render=node=>{
    if(node.kind==='field'||node.kind==='choice'){
      const label=document.createElement('label');label.textContent=node.label;
      const input=document.createElement(node.kind==='choice'?'select':node.multiline?'textarea':'input');
      input.dataset.field=node.id;input.id=`field-${node.id}`;
      input.autocomplete='off';if(passwordFields.includes(node.id))input.type='password';
      if(node.kind==='choice')for(const option of node.options){const e=document.createElement('option');e.textContent=option.label;e.value=option.value;input.append(e)}
      input.value=fields[node.id]??node.value;input.disabled=isBusy();
      input.oninput=()=>{if(node.kind==='field')fields[node.id]=input.value};
      if(node.kind==='choice')input.onchange=()=>send(node,input.value);
      label.append(input);return label;
    }
    const element=document.createElement(node.kind==='button'?'button':node.kind==='text'?'p':'div');
    element.dataset.node=node.id;
    if(node.kind==='text'){element.textContent=node.text;element.className=node.tone??''}
    else if(node.kind==='button'){element.textContent=node.text;element.disabled=isBusy()||node.enabled===false;element.onclick=()=>send(node)}
    else{element.className=node.kind;for(const child of node.children??[])element.append(render(child))}
    return element;
  };
  window.solvitPageUpdate=next=>{
    if(pendingToken!==null){if(next.busy)pendingBusy=true;if(next.token!==pendingToken||pendingBusy&&!next.busy)pendingToken=null}
    state=next;
    for(const [key,value]of Object.entries(next.theme))document.documentElement.style.setProperty(`--${key}`,value);
    if(token!==next.token){token=next.token;fields=Object.fromEntries(flatten(next.view).filter(n=>n.kind==='field').map(n=>[n.id,n.value]));
      root.replaceChildren(...next.view.nodes.map(render));footer.replaceChildren(...(next.view.footer??[]).map(render));root.scrollTop=0;
    }
    disable();
    window.pluginPageReady=true;
  };
  solvitHost.onmessage=event=>{
    const message=JSON.parse(event.data);if(message.type==='confirmation'&&message.token!==pendingToken)return;
    pendingToken=null;disable();
    if(message.type==='error'){
      const texts={zh:'页面操作未完成，请重试',en:'Page action failed. Please retry.',ja:'操作に失敗しました。再試行してください。',ko:'작업에 실패했습니다. 다시 시도하세요.',es:'La acción falló. Reinténtalo.',fr:'Échec de l’action. Réessayez.'};
      const error=document.createElement('p');error.setAttribute('role','alert');error.textContent=texts[state?.locale?.split('-')[0]]??texts.en;root.prepend(error);
    }
  };
  solvitHost.postMessage(JSON.stringify({type:'ready'}));
}

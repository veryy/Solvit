import {mountPage} from './browser-page.mjs';

// Plugin-owned presentation. Existing controls keep their host event and confirmation handlers.
export function mountCollectionPage(root,footer,{key,decorate,...options}){
 mountPage(root,footer,options);
 const update=window.solvitPageUpdate,positions=new Map();
 let token,lastKey;
 window.solvitPageUpdate=state=>{
  const changed=token!==state.token;
  if(changed&&lastKey!==undefined)positions.set(lastKey,{scroll:root.scrollTop,open:new Set(Array.from(root.querySelectorAll('details[open]'),n=>n.id))});
  update(state);
  if(!changed)return;
  const nextKey=key(state);
  decorate(root,footer,state);
  const previous=nextKey===lastKey||nextKey==='list'?positions.get(nextKey):null;
  if(previous){
   for(const details of root.querySelectorAll('details'))details.open=previous.open.has(details.id);
   root.scrollTop=previous.scroll;
  }
  token=state.token;lastKey=nextKey;
 };
}
export const element=(tag,className,text)=>{
 const node=document.createElement(tag);if(className)node.className=className;if(text!==undefined)node.textContent=text;return node;
};
export function iconButton(button,symbol){
 const label=button.textContent;button.title=label;button.setAttribute('aria-label',label);button.textContent=symbol==='search'?'':symbol;if(symbol==='search'){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.innerHTML='<circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="m16 16 5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>';button.append(svg)}button.classList.add('collection-icon');return button;
}
export function disclosure(id,label,children){
 const details=element('details','collection-disclosure');details.id=id;
 details.append(element('summary',null,label),...children);return details;
}

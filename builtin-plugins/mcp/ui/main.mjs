import {mountCollectionPage,element,disclosure} from '../../../plugin-sdk/src/collection-page.mjs';
const labels={
 zh:['搜索连接','清空搜索','管理','高级选项','没有匹配的连接','连接'],en:['Search connections','Clear search','Manage','Advanced options','No matching connections','connections'],
 ja:['接続を検索','検索をクリア','管理','詳細設定','一致する接続がありません','接続'],ko:['연결 검색','검색 지우기','관리','고급 옵션','일치하는 연결 없음','연결'],
 es:['Buscar conexiones','Borrar búsqueda','Gestionar','Opciones avanzadas','Sin conexiones coincidentes','conexiones'],fr:['Rechercher des connexions','Effacer la recherche','Gérer','Options avancées','Aucune connexion correspondante','connexions']
};
let query='';
mountCollectionPage(document.getElementById('content'),document.getElementById('actions'),{
 passwordFields:['token','env','headers','config'],
 key:state=>state.view.nodes.some(n=>n.id==='transports')?'edit':state.view.nodes.some(n=>n.id==='config')?'import':'list',
 decorate(root,footer,state){
  const t=labels[state.locale.split(/[-_]/)[0]]??labels.en;
  const node=id=>document.querySelector(`[data-node="${id}"]`),editing=!!document.getElementById('field-name');
  root.classList.toggle('mcp-editor',editing);root.classList.toggle('mcp-library',!editing&&!document.getElementById('field-config'));
  if(editing){
   const stdio=!!document.getElementById('field-command');
   for(const kind of ['http','stdio']){const button=node('transport_'+kind);button.setAttribute('aria-pressed',String((kind==='stdio')===stdio))}
   const fields=(stdio?['env','cwd']:['headers']).map(id=>document.getElementById('field-'+id)?.parentElement).filter(Boolean);
   if(fields.length)root.append(disclosure('mcp-advanced',t[3],fields));
   const clear=node('clear_auth');if(clear){clear.classList.add('destructive');const advanced=document.getElementById('mcp-advanced');advanced.append(clear)}
   return;
  }
  if(document.getElementById('field-config'))return;
  const cards=Array.from(root.querySelectorAll('.card'));
  for(const card of cards){
   const index=card.dataset.node.slice(7),actions=node('actions_'+index);
   const heading=element('div','connection-heading');node('name_'+index).before(heading);heading.append(node('name_'+index),node('state_'+index));
   const toggle=node('toggle_'+index),remove=node('delete_'+index);remove.classList.add('destructive');
   const management=disclosure('connection-manage-'+index,t[2],[toggle,remove]);actions.after(management);
   const stateNode=state.view.nodes.find(n=>n.id===card.dataset.node)?.children?.find(n=>n.id==='actions_'+index)?.children?.find(n=>n.id==='probe_'+index);
   // Only show enabled/disabled presentation from the supplied state, never infer connection success.
   card.classList.toggle('connection-disabled',stateNode?.enabled===false&&!node('workspace_hint_'+index));
  }
  if(!cards.length)return;
  const toolbar=element('div','collection-search'),input=element('input');input.type='search';input.id='connection-query';input.placeholder=t[0];input.setAttribute('aria-label',t[0]);input.value=query;
  const clear=element('button','collection-icon','×');clear.id='clear-connections';clear.type='button';clear.title=t[1];clear.setAttribute('aria-label',t[1]);
  toolbar.append(input,clear);root.prepend(toolbar);
  const count=element('p','collection-count');count.id='collection-count';toolbar.after(count);
  const empty=element('p','collection-empty',t[4]);empty.id='connection-empty';count.after(empty);
  const filter=()=>{
   query=input.value;const needle=query.trim().toLocaleLowerCase();let visible=0;
   for(const card of cards){const index=card.dataset.node.slice(7),text=[node('name_'+index).textContent,node('url_'+index).textContent].join(' ');card.hidden=!text.toLocaleLowerCase().includes(needle);if(!card.hidden)visible++}
   count.textContent=`${visible} / ${cards.length} ${t[5]}`;empty.hidden=visible>0;clear.hidden=!query;
  };
  input.oninput=filter;clear.onclick=()=>{input.value='';filter();input.focus()};filter();
 }
});

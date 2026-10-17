import {mountCollectionPage,element,iconButton,disclosure} from '../../../plugin-sdk/src/collection-page.mjs';
const labels={zh:['清空搜索','技能','关于此技能','没有匹配的技能，请调整或清空搜索。'],en:['Clear search','skills','About this skill','No matching skills. Change or clear your search.'],ja:['検索をクリア','スキル','このスキルについて','一致するスキルがありません。検索を変更またはクリアしてください。'],ko:['검색 지우기','스킬','스킬 정보','일치하는 스킬이 없습니다. 검색을 변경하거나 지우세요.'],es:['Borrar búsqueda','skills','Acerca del skill','Sin skills coincidentes. Cambia o borra la búsqueda.'],fr:['Effacer la recherche','skills','À propos du skill','Aucun skill correspondant. Modifiez ou effacez la recherche.']};
const content=document.getElementById('content'),footer=document.getElementById('actions');
mountCollectionPage(content,footer,{
 key:state=>state.view.nodes.some(n=>n.id==='query')?'list':`${state.view.nodes.some(n=>n.id==='preview')?'preview':'detail'}:${state.view.nodes.find(n=>n.id==='name')?.text}:${state.view.nodes.find(n=>n.id==='resource')?.value}`,
 decorate(root,actions,state){
  const t=labels[state.locale.split(/[-_]/)[0]]??labels.en;
  const node=id=>document.querySelector(`[data-node="${id}"]`),field=document.getElementById('field-query');
  root.classList.toggle('skill-library',!!field);root.classList.toggle('skill-detail',!field);
  if(field){
   const toolbar=element('div','collection-search'),label=field.parentElement;
   label.classList.add('collection-search-field');field.setAttribute('aria-label',label.firstChild.textContent);field.placeholder=label.firstChild.textContent;field.type='search';
   const search=node('search');toolbar.append(label,iconButton(search,'search'));
   const clear=element('button','collection-icon','×');clear.id='clear-search';clear.type='button';clear.title=t[0];clear.setAttribute('aria-label',t[0]);
   const changed=()=>{clear.hidden=!field.value};field.addEventListener('input',changed);changed();
   clear.onclick=()=>{field.value='';field.dispatchEvent(new Event('input'));search.click()};toolbar.append(clear,iconButton(node('refresh'),'↻'));
   field.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();search.click()}});
   root.prepend(toolbar);
   const count=element('p','collection-count',`${root.querySelectorAll('.card').length} ${t[1]}`);count.id='collection-count';toolbar.after(count);
   for(const card of root.querySelectorAll('.card'))card.classList.add('skill-tile');
   // Old read-only packages have the same nodes but predate the distinct no-match copy.
   const empty=node('empty');if(empty&&field.value.trim()&&!root.querySelector('.card'))empty.textContent=t[3];
  }else{
   const notice=node('notice');if(notice){const info=disclosure('skill-about',t[2],[notice]);root.append(info)}
   const body=Array.from(root.querySelectorAll('[data-node^="body_"]'));
   if(body.length){const panel=element('section','skill-resource');panel.setAttribute('aria-label',document.querySelector('label')?.textContent??'SKILL.md');body[0].before(panel);panel.append(...body)}
  }
 }
});

import css from './directory-style.mjs';
const stylesheet=document.createElement('style');stylesheet.textContent=css;document.head.append(stylesheet);
// The plugin owns file-browser presentation; existing host-backed buttons keep
// their handlers, identifiers and confirmations when moved into these groups.
const labels={
 zh:['新建','导入','更多','新建文件或导入已有文件，开始使用这个目录。'],
 en:['New','Import','More','Create a file or import existing files to get started.'],
 ja:['新規','インポート','その他','ファイルを作成するか、既存のファイルをインポートしてください。'],
 ko:['새로 만들기','가져오기','더 보기','파일을 만들거나 기존 파일을 가져오세요.'],
 es:['Nuevo','Importar','Más','Crea un archivo o importa archivos para empezar.'],
 fr:['Nouveau','Importer','Plus','Créez un fichier ou importez des fichiers existants.']
};
const paths={folder:'M3 7V5h6l2 2h10v12H3Z',file:'M6 3h8l4 4v14H6ZM14 3v5h4',refresh:'M20 7v5h-5M20 12a8 8 0 1 0-2 6',up:'m5 12 7-7 7 7M12 5v15',more:'M5 12h.01M12 12h.01M19 12h.01',chevron:'m8 10 4 4 4-4'};
function icon(name){
 const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
 svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');svg.setAttribute('focusable','false');
 const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',paths[name]);svg.append(path);return svg;
}
function closeMenus(except){document.querySelectorAll('details.workspace-menu[open]').forEach(menu=>{if(menu!==except)menu.open=false})}
document.addEventListener('pointerdown',event=>{if(!event.target.closest?.('.workspace-menu'))closeMenus()});
document.addEventListener('keydown',event=>{if(event.key==='Escape'){const open=document.querySelector('details.workspace-menu[open]');if(open){closeMenus();open.querySelector('summary').focus();event.preventDefault()}}});
function menu(id,label,buttons,compact=false){
 if(!buttons.length)return null;
 const details=document.createElement('details');details.id=id;details.className='workspace-menu';
 const summary=document.createElement('summary');summary.setAttribute('aria-label',label);summary.title=label;
 if(compact){summary.append(icon('more'));summary.classList.add('workspace-icon-button')}
 else{summary.append(document.createTextNode(label),icon('chevron'))}
 const panel=document.createElement('div');panel.className='workspace-menu-panel';panel.append(...buttons);
 details.append(summary,panel);details.addEventListener('toggle',()=>{if(details.isConnected&&details.open){
  closeMenus(details);const bounds=details.closest('main').getBoundingClientRect(),anchor=summary.getBoundingClientRect();
  const below=bounds.bottom-anchor.bottom-8,above=anchor.top-bounds.top-8,up=below<Math.min(panel.scrollHeight,320)&&above>below;
  panel.classList.toggle('above',up);panel.style.maxHeight=`${Math.max(44,Math.min(320,up?above:below))}px`;
  // Git may expose only New. Align to real space, not DOM child order.
  panel.style.maxWidth=`${Math.max(44,bounds.width-8)}px`;panel.style.insetInlineStart='0px';
  const rect=panel.getBoundingClientRect(),shift=Math.max(bounds.left+4-rect.left,Math.min(0,bounds.right-4-rect.right));
  panel.style.insetInlineStart=`${shift}px`;
 }});
 panel.addEventListener('click',event=>{if(event.target.closest('button'))details.open=false});return details;
}
function iconButton(button,name){if(!button)return;const label=button.textContent;button.setAttribute('aria-label',label);button.title=label;button.replaceChildren(icon(name));button.classList.add('workspace-icon-button')}
export function polishDirectory(root,footer,locale){
 const actions=root.querySelector('[data-node="directory_actions"]');if(!actions){root.classList.remove('workspace-directory');return}
 const t=labels[locale?.split(/[-_]/)[0]]??labels.en;root.classList.add('workspace-directory');
 const get=id=>document.querySelector(`[data-node="${id}"]`);
 const nav=document.createElement('nav');nav.className='workspace-navigation';
 const toggle=document.getElementById('tree-toggle');if(toggle){toggle.prepend(icon('folder'));nav.append(toggle)}
 const parent=footer.querySelector('[data-node="parent"]');if(parent){iconButton(parent,'up');nav.append(parent)}
 const path=get('directory');path.classList.add('workspace-path');path.title=path.textContent;nav.append(path);
 const refresh=footer.querySelector('[data-node="refresh"]');if(refresh){iconButton(refresh,'refresh');nav.append(refresh)}
 const groups=[menu('workspace-new-menu',t[0],['new','mkdir'].map(get).filter(Boolean)),menu('workspace-import-menu',t[1],['selectImport','chatFiles'].map(get).filter(Boolean)),menu('workspace-more-menu',t[2],['backupOpen'].map(get).filter(Boolean))];
 actions.className='workspace-actions';actions.replaceChildren(...groups.filter(Boolean));root.prepend(nav);
 const empty=get('empty');if(empty){empty.classList.add('workspace-empty');empty.prepend(icon('folder'));const hint=document.createElement('span');hint.className='caption';hint.textContent=t[3];empty.append(hint)}
 root.querySelectorAll('.card[data-node^="entry_"]').forEach(row=>{
  const index=row.dataset.node.slice(6),name=get(`file_name_${index}`),size=get(`file_size_${index}`),open=get(`file_${index}`);
  if(!name||!open)return;
  const directory=name.textContent.startsWith('▸ '),title=directory?name.textContent.slice(2):name.textContent;
  row.className='workspace-file';open.className='workspace-file-open';open.title=title;open.setAttribute('aria-label',title);
  const copy=document.createElement('span');copy.className='workspace-file-copy';name.className='workspace-file-name';name.textContent=title;copy.append(name);if(size)copy.append(size);
  open.replaceChildren(icon(directory?'folder':'file'),copy);row.prepend(open);
  const more=menu(`workspace-file-menu-${index}`,`${t[2]} · ${title}`,[get(`rename_${index}`),get(`delete_${index}`)].filter(Boolean),true);if(more)row.append(more);
 });
 footer.classList.toggle('workspace-directory-footer',true);
}

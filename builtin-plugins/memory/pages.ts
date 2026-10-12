import type { PageNode, PluginPageInput, PluginPageView, PluginContext } from '../../plugin-sdk/src/index';
import type { MemoryDatabase, MemoryFact } from './index';

/** Mutations must compare expected inside the existing atomic memory transaction. */
export interface MemoryPageBackend {
  read(context: PluginContext): Promise<MemoryDatabase>;
  save(input: { expected: MemoryFact | null; text: string; category: string; tags: string[] }, context: PluginContext): Promise<string>;
  remove(expected: MemoryFact, context: PluginContext): Promise<void>;
  tasks?: (input: PluginPageInput, context: PluginContext) => Promise<PluginPageView>;
}
const en = {
  title:'Memory', query:'Search memories', category:'Category (empty for all)', role:'Role name or ID (empty for all)',
  filter:'Filter', clear:'Clear', new:'New memory', graph:'Relations', profiles:'Profile', documents:'Documents', tasks:'Background tasks',
  refresh:'Refresh', back:'Back', edit:'Edit', remove:'Delete', save:'Save', text:'Memory content', tags:'Tags, separated by commas',
  manual:'Shared manual memory', unassigned:'Unassigned role', source:'Original user quote', updated:'Updated', id:'Record ID',
  protected:'Edited manually; protected from automatic replacement', empty:'No matching records', previous:'Previous', next:'Next',
  removeConfirm:'Delete this memory and its relations?', discard:'Discard edits and reload?', changed:'This memory changed. Reload it before saving.',
  failed:'Unable to complete this action. Reopen and try again.', invalid:'Enter nonempty content and category, with at most 12 tags of 40 characters.',
  shared:'Profiles and documents are shared records; role filters apply to facts.', total:'Records', page:'Page', details:'Details',
};
const words: Record<string, typeof en> = {
  en,
  zh: {title:'记忆',query:'搜索记忆',category:'类别（留空显示全部）',role:'角色名称或 ID（留空显示全部）',filter:'筛选',clear:'清除',new:'新建记忆',graph:'关系图',profiles:'画像',documents:'文档',tasks:'后台任务',refresh:'刷新',back:'返回',edit:'编辑',remove:'删除',save:'保存',text:'记忆内容',tags:'标签，用逗号分隔',manual:'手动共享记忆',unassigned:'未指定角色',source:'用户原文引用',updated:'更新时间',id:'记录 ID',protected:'已人工修改，不会被自动替换',empty:'暂无匹配资料',previous:'上一页',next:'下一页',removeConfirm:'删除这条记忆及其关联关系？',discard:'丢弃未保存改动并重新载入？',changed:'记忆已经变化，请重新载入后再保存。',failed:'操作未完成，请重新打开后重试。',invalid:'请填写内容和类别；最多 12 个标签，每个不超过 40 字符。',shared:'画像和文档是共享资料；角色筛选用于事实。',total:'资料数',page:'页',details:'详情'},
  ja: {title:'記憶',query:'記憶を検索',category:'分類（空欄はすべて）',role:'ロール名または ID（空欄はすべて）',filter:'絞り込む',clear:'クリア',new:'記憶を作成',graph:'関係図',profiles:'プロフィール',documents:'文書',tasks:'バックグラウンドタスク',refresh:'更新',back:'戻る',edit:'編集',remove:'削除',save:'保存',text:'記憶の内容',tags:'タグ（カンマ区切り）',manual:'手動の共有記憶',unassigned:'ロール未指定',source:'ユーザー原文の引用',updated:'更新日時',id:'記録 ID',protected:'手動編集済み・自動置換から保護',empty:'一致する資料がありません',previous:'前のページ',next:'次のページ',removeConfirm:'この記憶と関連する関係を削除しますか？',discard:'編集を破棄して再読み込みしますか？',changed:'記憶が変更されています。再読み込みしてから保存してください。',failed:'操作を完了できません。開き直して再試行してください。',invalid:'内容と分類を入力してください。タグは最大12個、各40文字までです。',shared:'プロフィールと文書は共有資料です。ロールの絞り込みは事実に適用されます。',total:'資料数',page:'ページ',details:'詳細'},
  ko: {title:'기억',query:'기억 검색',category:'분류 (비우면 전체)',role:'역할 이름 또는 ID (비우면 전체)',filter:'필터',clear:'초기화',new:'새 기억',graph:'관계도',profiles:'프로필',documents:'문서',tasks:'백그라운드 작업',refresh:'새로고침',back:'뒤로',edit:'편집',remove:'삭제',save:'저장',text:'기억 내용',tags:'쉼표로 구분한 태그',manual:'수동 공유 기억',unassigned:'역할 미지정',source:'사용자 원문 인용',updated:'수정 시간',id:'기록 ID',protected:'수동 편집됨; 자동 교체 방지',empty:'일치하는 자료가 없습니다',previous:'이전',next:'다음',removeConfirm:'이 기억과 관련 관계를 삭제할까요?',discard:'편집을 버리고 다시 불러올까요?',changed:'기억이 변경되었습니다. 다시 불러온 뒤 저장하세요.',failed:'작업을 완료하지 못했습니다. 다시 열고 재시도하세요.',invalid:'내용과 분류를 입력하세요. 태그는 최대 12개, 각 40자입니다.',shared:'프로필과 문서는 공유 자료입니다. 역할 필터는 사실에 적용됩니다.',total:'자료 수',page:'페이지',details:'상세'},
  es: {title:'Memoria',query:'Buscar recuerdos',category:'Categoría (vacío para todas)',role:'Nombre o ID del rol (vacío para todos)',filter:'Filtrar',clear:'Limpiar',new:'Nuevo recuerdo',graph:'Relaciones',profiles:'Perfil',documents:'Documentos',tasks:'Tareas en segundo plano',refresh:'Actualizar',back:'Volver',edit:'Editar',remove:'Eliminar',save:'Guardar',text:'Contenido del recuerdo',tags:'Etiquetas separadas por comas',manual:'Recuerdo manual compartido',unassigned:'Sin rol asignado',source:'Cita original del usuario',updated:'Actualizado',id:'ID del registro',protected:'Editado manualmente; protegido de reemplazos automáticos',empty:'No hay registros coincidentes',previous:'Anterior',next:'Siguiente',removeConfirm:'¿Eliminar este recuerdo y sus relaciones?',discard:'¿Descartar los cambios y recargar?',changed:'Este recuerdo cambió. Recárgalo antes de guardar.',failed:'No se pudo completar la acción. Vuelve a abrir e inténtalo otra vez.',invalid:'Introduce contenido y categoría; hasta 12 etiquetas de 40 caracteres.',shared:'Los perfiles y documentos son compartidos; el filtro de rol se aplica a los hechos.',total:'Registros',page:'Página',details:'Detalles'},
  fr: {title:'Mémoire',query:'Rechercher des souvenirs',category:'Catégorie (vide pour toutes)',role:'Nom ou ID du rôle (vide pour tous)',filter:'Filtrer',clear:'Effacer',new:'Nouveau souvenir',graph:'Relations',profiles:'Profil',documents:'Documents',tasks:'Tâches en arrière-plan',refresh:'Actualiser',back:'Retour',edit:'Modifier',remove:'Supprimer',save:'Enregistrer',text:'Contenu du souvenir',tags:'Étiquettes séparées par des virgules',manual:'Souvenir manuel partagé',unassigned:'Rôle non attribué',source:'Citation originale de l’utilisateur',updated:'Mis à jour',id:'ID du document',protected:'Modifié manuellement ; protégé du remplacement automatique',empty:'Aucun résultat',previous:'Précédent',next:'Suivant',removeConfirm:'Supprimer ce souvenir et ses relations ?',discard:'Abandonner les modifications et recharger ?',changed:'Ce souvenir a changé. Rechargez-le avant de l’enregistrer.',failed:'Action impossible. Rouvrez la page et réessayez.',invalid:'Saisissez un contenu et une catégorie ; au plus 12 étiquettes de 40 caractères.',shared:'Les profils et documents sont partagés ; le filtre de rôle s’applique aux faits.',total:'Documents',page:'Page',details:'Détails'},
};
const brief = (value: string, length: number) => { const letters=Array.from(value);return letters.slice(0,length).join('')+(letters.length>length?'…':''); };
const copy = (fact: MemoryFact): MemoryFact => JSON.parse(JSON.stringify(fact));
function canonical(value: unknown): unknown {
  if(Array.isArray(value))return value.map(canonical);
  if(value!==null&&typeof value==='object')return Object.fromEntries(Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([key,v])=>[key,canonical(v)]));
  return value;
}
export const sameMemoryFact = (a: MemoryFact | undefined, b: MemoryFact | null) => a !== undefined && b !== null && JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
type Button = Extract<PageNode, { kind:'button' }>;
const text = (id: string, value: string, tone:'body'|'title'|'caption'='body'): PageNode => ({id,kind:'text',text:value,tone});
const button = (id: string, label: string, action = id, confirm?: string): Button => ({id,kind:'button',text:label,action,...(confirm ? {confirm} : {})});
const field = (id: string, label: string, value: string, multiline = false): PageNode => ({id,kind:'field',label,value,multiline});

/** Each page engine gets its own search/navigation/edit snapshot. No chat lifecycle state. */
export function createMemoryPage(backend: MemoryPageBackend) {
  let mode:'list'|'detail'|'edit'|'new'|'graph'|'profiles'|'documents'|'tasks'='list';
  let query='', category='', role='', offset=0, selected:string|null=null;
  let expected:MemoryFact|null=null, visible:MemoryFact[]=[], graphFacts:MemoryFact[]=[];
  let graphReturn:'list'|'detail'='list', center:string|null=null;
  return async (input:PluginPageInput, context:PluginContext):Promise<PluginPageView> => {
    const t = words[input.locale.split('-')[0]] ?? words.zh;
    const event = input.event;
    if (mode==='tasks' && event?.action!=='back' && backend.tasks) return backend.tasks(input,context);
    let db = await backend.read(context);
    let message='';
    const record = () => db.facts.find(fact=>fact.id===selected);
    try {
      const action=event?.action;
      if (action==='filter') { query=(event!.fields.query??'').trim().slice(0,160);category=(event!.fields.category??'').trim().slice(0,40);role=(event!.fields.role??'').trim().slice(0,80);offset=0; }
      else if (action==='clear') { query=category=role='';offset=0; }
      else if (action==='next') offset+=20;
      else if (action==='previous') offset=Math.max(0,offset-20);
      else if (action?.startsWith('open_')) { selected=visible[Number(action.slice(5))]?.id??null;mode=selected ? 'detail':'list'; }
      else if (action==='select_node') { selected=graphFacts[Number(event!.value?.slice(1))]?.id??null;mode=selected ? 'detail':'list'; }
      else if (action==='new') { selected=null;expected=null;mode='new'; }
      else if (action==='edit') { const fact=record(); if(fact){expected=copy(fact);mode='edit';} }
      else if (action==='reload') { const fact=record(); expected=fact ? copy(fact):null; if(!fact)mode='list'; }
      else if (action==='back') { mode=mode==='graph' ? graphReturn : mode==='edit' ? 'detail':'list'; }
      else if (action==='profiles'||action==='documents') { mode=action;offset=0; }
      else if (action==='tasks'&&backend.tasks) { mode='tasks';return backend.tasks({...input,event:null},context); }
      else if (action==='graph') { center=mode==='detail' ? selected:null;graphReturn=mode==='detail' ? 'detail':'list';mode='graph'; }
      else if (action==='save' && (mode==='edit'||mode==='new')) {
        const body=event!.fields.text?.trim()??'', cat=event!.fields.edit_category?.trim()??'';
        const tags=[...new Set((event!.fields.tags??'').split(/[,，、\n]/).map(s=>s.trim()).filter(Boolean))];
        if(!body||body.length>4000||!cat||cat.length>40||tags.length>12||tags.some(tag=>tag.length>40))message=t.invalid;
        else if(mode==='edit'&&!sameMemoryFact(record(),expected))message=t.changed;
        else { selected=await backend.save({expected:mode==='edit'?expected:null,text:body,category:cat,tags},context);mode='detail';db=await backend.read(context); }
      } else if (action==='remove'&&mode==='detail') {
        if(!sameMemoryFact(record(),expected))message=t.changed;
        else { await backend.remove(expected!,context);selected=null;mode='list';db=await backend.read(context); }
      }
    } catch { message=t.failed; }
    const nodes:PageNode[]=[];
    if(message)nodes.push(text('message',message,'caption'));
    const all=db.facts.filter(fact =>
      (!category||fact.category===category) && (!role||`${fact.source?.roleName??(fact.source?t.unassigned:t.manual)} ${fact.source?.roleId??''}`.toLowerCase().includes(role.toLowerCase())) &&
      (!query||`${fact.text} ${fact.category} ${fact.tags.join(' ')} ${fact.source?.quote??''}`.toLowerCase().includes(query.toLowerCase())))
      .sort((a,b)=>b.updatedAt.localeCompare(a.updatedAt));
    if(mode==='list') {
      offset=Math.min(offset,Math.max(0,Math.floor((all.length-1)/20)*20));visible=all.slice(offset,offset+20);
      nodes.push(text('count',`${t.total}: ${all.length}`,'caption'),
        {id:'filters',kind:'row',children:[field('query',t.query,query),button('filter',t.filter),button('clear',t.clear)]},
        field('category',t.category,category),field('role',t.role,role));
      visible.forEach((fact,i)=>nodes.push({id:`fact_${i}`,kind:'card',children:[text(`body_${i}`,brief(fact.text,180)),
        text(`meta_${i}`,`${fact.category} · ${fact.source?.roleName??(fact.source?t.unassigned:t.manual)}`,'caption'),button(`open_${i}`,t.details)]}));
      if(!visible.length)nodes.push(text('empty',t.empty));
      nodes.push({id:'pagination',kind:'row',children:[{...button('previous',t.previous),enabled:offset>0},
        text('page',`${t.page} ${Math.floor(offset/20)+1}/${Math.max(1,Math.ceil(all.length/20))}`),{...button('next',t.next),enabled:offset+20<all.length}]});
      return {title:t.title,nodes,footer:[button('new',t.new),button('graph',t.graph),button('profiles',t.profiles),button('documents',t.documents),
        ...(backend.tasks?[button('tasks',t.tasks)]:[]),button('refresh',t.refresh)]};
    }
    if(mode==='detail') {
      const fact=record();
      if(!fact)return {title:t.title,nodes:[...nodes,text('missing',t.empty)],footer:[button('back',t.back)]};
      expected=copy(fact);
      nodes.push(text('body',fact.text),text('category',fact.category,'caption'),text('tags',fact.tags.join(' · '),'caption'),
        text('record_id',`${t.id}: ${fact.id}`,'caption'),text('updated',`${t.updated}: ${fact.updatedAt}`,'caption'));
      if(fact.source) nodes.push(text('source_title',t.source,'title'),text('quote',fact.source.quote),
        text('source_ids',`${fact.source.roleName??t.unassigned}\n${fact.source.roleId??''}\n${fact.source.sessionId}\n${fact.source.userMessageId}\n${fact.source.assistantMessageId}`,'caption'));
      else nodes.push(text('manual',t.manual,'caption'));
      if(fact.modifiedByUser)nodes.push(text('protected',t.protected,'caption'));
      return {title:t.title,nodes,footer:[button('back',t.back),button('edit',t.edit),button('graph',t.graph),button('remove',t.remove,'remove',t.removeConfirm)]};
    }
    if(mode==='edit'||mode==='new') {
      if(mode==='new')nodes.push(text('scope',t.manual,'caption'));
      nodes.push(field('text',t.text,event?.action==='save'?event.fields.text??'':expected?.text??'',true),
        field('edit_category',t.category.split(/[（(]/)[0].trim(),event?.action==='save'?event.fields.edit_category??'':expected?.category??'fact'),
        field('tags',t.tags,event?.action==='save'?event.fields.tags??'':expected?.tags.join(', ')??''));
      return {title:mode==='new'?t.new:t.edit,nodes,footer:[button('back',t.back),button('save',t.save),...(mode==='edit'?[button('reload',t.refresh,'reload',t.discard)]:[])]};
    }
    if(mode==='graph') {
      const neighbors=center ? new Set(db.edges.filter(e=>e.from===center||e.to===center).flatMap(e=>[e.from,e.to])):null;
      graphFacts=center ? db.facts.filter(f=>f.id===center||neighbors!.has(f.id)):all;
      const ids=new Map(graphFacts.map((fact,i)=>[fact.id,`g${i}`]));
      nodes.push(text('graph_count',`${t.total}: ${graphFacts.length}`,'caption'));
      if(graphFacts.length)nodes.push({id:'relations',kind:'graph',action:'select_node',
        nodes:graphFacts.map((f,i)=>({id:`g${i}`,label:brief(f.text,36)})),
        edges:db.edges.filter(e=>ids.has(e.from)&&ids.has(e.to)).map(e=>({from:ids.get(e.from)!,to:ids.get(e.to)!,label:brief(e.relation,24)}))});
      else nodes.push(text('empty',t.empty));
      return {title:t.graph,nodes,footer:[button('back',t.back),button('refresh',t.refresh)]};
    }
    nodes.push(text('shared',t.shared,'caption'));
    if(query)nodes.push(text('query_summary',`${t.query}: ${query}`,'caption'));
    const records=mode==='profiles' ? Object.entries(db.profile).map(([key,value])=>({label:key,body:value.value})) :
      [...new Set(db.chunks.map(c=>c.document))].map(id=>({label:db.chunks.find(c=>c.document===id)!.title,body:db.chunks.filter(c=>c.document===id).map(c=>c.text).join('\n')}));
    const matches=records.filter(r=>!query||`${r.label} ${r.body}`.toLowerCase().includes(query.toLowerCase()));
    offset=Math.min(offset,Math.max(0,Math.floor((matches.length-1)/20)*20));
    matches.slice(offset,offset+20).forEach((r,i)=>nodes.push({id:`record_${i}`,kind:'card',children:[text(`label_${i}`,brief(r.label,128),'title'),text(`body_${i}`,brief(r.body,600))]}));
    if(!matches.length)nodes.push(text('empty',t.empty));
    nodes.push({id:'pagination',kind:'row',children:[{...button('previous',t.previous),enabled:offset>0},{...button('next',t.next),enabled:offset+20<matches.length}]});
    return {title:mode==='profiles'?t.profiles:t.documents,nodes,footer:[button('back',t.back),button('refresh',t.refresh),...(query?[button('clear',t.clear)]:[])]};
  };
}

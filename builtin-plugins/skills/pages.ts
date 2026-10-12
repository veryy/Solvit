import type {PluginContext,PluginPageInput,PluginPageView,PageNode,JsonValue,JsonObject} from '../../plugin-sdk/src/index';
import type {SkillDocument} from '../../plugin-sdk/src/skills';
type Skill=SkillDocument&{files:Record<string,string>};
type Database={format:1;skills:Skill[]};
type Store={decode(data:unknown):Database;skillFiles(files:unknown,directory?:unknown):Skill;metadata(skill:Skill):JsonObject;
  save(db:Database,ctx:PluginContext,expected?:JsonValue):Promise<void>};
const words={
 zh:['技能库','搜索技能','导入技能','刷新','查看','返回','导出','删除','文本资源','技能说明','暂无技能，可导入 SKILL.md、文本 ZIP 或 JSON 备份。','确认导入这个技能？','确认替换同名技能及其全部资源？','删除这个技能及其文本资源？角色中的旧绑定将不再加载它。','数据已变化，请重新查看后操作。','文件格式、技能名称或资源路径不合法，或存储容量已满。','已导入','已导出','已删除','保留现有技能','替换现有技能','同名技能已存在','已取消','正文按需加载；脚本仅作为文本，技能不会授予执行权限。','导入预览'],
 en:['Skills','Search skills','Import skill','Refresh','View','Back','Export','Delete','Text resources','Instructions','No skills. Import SKILL.md, a text ZIP or a JSON backup.','Import this skill?','Replace this skill and all its resources?','Delete this skill and its resources? Old role bindings will no longer load it.','Data changed. Review it again before acting.','Invalid file, skill name or resource path, or storage is full.','Imported','Exported','Deleted','Keep existing','Replace existing','A skill with this name exists','Cancelled','Instructions load on demand. Scripts stay text; skills grant no execution permissions.','Import preview'],
 ja:['スキル','スキルを検索','インポート','更新','表示','戻る','エクスポート','削除','テキストリソース','手順','スキルがありません。SKILL.md、テキストZIP、JSONをインポートできます。','このスキルをインポートしますか？','同名スキルと全リソースを置き換えますか？','スキルとリソースを削除しますか？古いロール設定では読み込めなくなります。','データが変更されました。確認し直してください。','ファイル形式、名前、パスが不正か、保存容量が不足しています。','インポート済み','エクスポート済み','削除済み','既存を維持','既存を置換','同名スキルが存在します','キャンセル済み','手順は必要時に読み込みます。スクリプトはテキストで、実行権限は付与しません。','インポート確認'],
 ko:['스킬','스킬 검색','가져오기','새로 고침','보기','뒤로','내보내기','삭제','텍스트 리소스','사용 지침','스킬이 없습니다. SKILL.md, 텍스트 ZIP 또는 JSON을 가져오세요.','이 스킬을 가져올까요?','같은 이름의 스킬과 모든 리소스를 바꿀까요?','스킬과 리소스를 삭제할까요? 기존 역할 연결은 더 이상 로드되지 않습니다.','데이터가 변경되었습니다. 다시 확인하세요.','파일, 이름, 경로가 잘못되었거나 저장 공간이 부족합니다.','가져옴','내보냄','삭제됨','기존 유지','기존 대체','같은 이름의 스킬이 있습니다','취소됨','지침은 필요할 때 로드합니다. 스크립트는 텍스트이며 실행 권한을 부여하지 않습니다.','가져오기 미리 보기'],
 es:['Skills','Buscar skills','Importar','Actualizar','Ver','Volver','Exportar','Eliminar','Recursos de texto','Instrucciones','Sin skills. Importa SKILL.md, un ZIP de texto o una copia JSON.','¿Importar este skill?','¿Reemplazar el skill y todos sus recursos?','¿Eliminar el skill y sus recursos? Los vínculos anteriores dejarán de cargarlo.','Los datos cambiaron. Revísalos otra vez.','Archivo, nombre o ruta inválidos, o almacenamiento lleno.','Importado','Exportado','Eliminado','Conservar','Reemplazar','Ya existe ese nombre','Cancelado','Las instrucciones se cargan cuando hacen falta. Los scripts son texto y no otorgan permisos.','Vista previa'],
 fr:['Skills','Rechercher','Importer','Actualiser','Voir','Retour','Exporter','Supprimer','Ressources texte','Instructions','Aucun skill. Importez SKILL.md, un ZIP texte ou une sauvegarde JSON.','Importer ce skill ?','Remplacer ce skill et toutes ses ressources ?','Supprimer le skill et ses ressources ? Les anciens rôles ne le chargeront plus.','Les données ont changé. Vérifiez-les à nouveau.','Fichier, nom ou chemin invalide, ou stockage plein.','Importé','Exporté','Supprimé','Conserver','Remplacer','Ce nom existe déjà','Annulé','Instructions chargées au besoin. Les scripts restent du texte et ne donnent aucun droit d’exécution.','Aperçu de l’import'],
};
type Button=Extract<PageNode,{kind:'button'}>;
const button=(id:string,text:string,action=id,confirm?:string):Button=>({id,kind:'button',text,action,...(confirm?{confirm}:{})});
const text=(id:string,value:string,tone:'body'|'title'|'caption'='body'):PageNode=>({id,kind:'text',text:value,tone});
const object=(value:unknown):value is Record<string,unknown>=>!!value&&typeof value==='object'&&!Array.isArray(value);
function importSkill(document:{text?:string;files?:Record<string,string>},store:Store):Skill {
 if(document.files){
  const main=Object.keys(document.files).filter(path=>path==='SKILL.md'||path.endsWith('/SKILL.md'));
  if(main.length!==1)throw Error('INVALID');
  const prefix=main[0].slice(0,-'SKILL.md'.length),directory=prefix.replace(/\/$/,'');
  if(prefix&&!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(directory)||!Object.keys(document.files).every(path=>path.startsWith(prefix)))throw Error('INVALID');
  return store.skillFiles(Object.fromEntries(Object.entries(document.files).map(([path,value])=>[path.slice(prefix.length),value])),directory||undefined);
 }
 if(typeof document.text!=='string')throw Error('INVALID');
 const source=document.text.replace(/^\uFEFF/,'');
 if(source.trimStart().startsWith('{')){
  const parsed:unknown=JSON.parse(source);
  if(!object(parsed)||parsed.format!==1)throw Error('INVALID');
  return store.skillFiles(parsed.files,parsed.directory);
 }
 return store.skillFiles({'SKILL.md':source});
}
export function createSkillsPage(store:Store){
 let mode:'list'|'detail'|'preview'='list',query='',names:string[]=[],selected:string|null=null,selectedVersion='';
 let pending:Skill|null=null,previous:string|null=null,replacement='no',resource='@instructions';
 return async(input:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const t=words[input.locale.split('-')[0] as keyof typeof words]??words.zh;
  let raw=await ctx.storage.get('skills.v1'),db=store.decode(raw),message='';
  const event=input.event,action=event?.action;
  const reload=async()=>{raw=await ctx.storage.get('skills.v1');db=store.decode(raw)};
  const persist=async()=>{await store.save(db,ctx,raw);await reload()};
  const current=()=>{
   const skill=db.skills.find(s=>s.name===selected);
   if(!skill||JSON.stringify(skill)!==selectedVersion)throw Error('CONFLICT');return skill;
  };
  try{
   if(action==='back'){mode='list';pending=null;selected=null;resource='@instructions'}
   else if(action==='search'){query=(event!.fields.query??'').trim().slice(0,256)}
   else if(action==='open'){
    selected=names[Number(event!.nodeId.slice(5))]??null;
    const skill=db.skills.find(s=>s.name===selected);if(!skill)throw Error('CONFLICT');
    selectedVersion=JSON.stringify(skill);resource='@instructions';mode='detail';
   }else if(action==='import'){
    if(!ctx.documents)throw Error('INVALID');
    const document=await ctx.documents.open();
    if(!document){message=t[22]}else{
     pending=importSkill(document,store);await reload();
     previous=JSON.stringify(db.skills.find(s=>s.name===pending!.name)??null);
     replacement='no';resource='@instructions';mode='preview';
    }
   }else if(action==='replace'){replacement=event!.value??'no'}
   else if(action==='resource'){resource=event!.value??'@instructions'}
   else if(action==='confirm_import'&&mode==='preview'&&pending){
    const existing=db.skills.find(s=>s.name===pending!.name)??null;
    if(JSON.stringify(existing)!==previous)throw Error('CONFLICT');
    if(existing&&replacement!=='yes')throw Error('INVALID');
    db.skills=db.skills.filter(s=>s.name!==pending!.name).concat(pending);await persist();
    message=t[16];pending=null;mode='list';
   }else if(action==='delete'&&mode==='detail'){
    current();db.skills=db.skills.filter(s=>s.name!==selected);await persist();mode='list';selected=null;message=t[18];
   }else if(action==='export'&&mode==='detail'){
    const skill=current();if(!ctx.documents)throw Error('INVALID');
    const saved=await ctx.documents.save(JSON.stringify({format:1,directory:skill.name,files:skill.files},null,2));message=saved?t[17]:t[22];
   }
  }catch(error){message=error instanceof Error&&error.message==='CONFLICT'?t[14]:t[15];await reload()}
  const nodes:PageNode[]=[];if(message)nodes.push(text('message',message));
  const footer:Button[]=[];
  if(mode==='list'){
   nodes.push({id:'query',kind:'field',label:t[1],value:query},{id:'search',kind:'button',text:t[1],action:'search'});
   const needle=query.toLocaleLowerCase();const visible=db.skills.filter(s=>(s.name+' '+s.description).toLocaleLowerCase().includes(needle));names=visible.map(s=>s.name);
   if(!visible.length)nodes.push(text('empty',t[10]));
   visible.forEach((s,index)=>nodes.push({id:`skill_${index}`,kind:'card',children:[text(`name_${index}`,s.name,'title'),text(`description_${index}`,Array.from(s.description).slice(0,180).join(''),'caption'),button(`open_${index}`,t[4],'open')]}));
   footer.push({...button('import',t[2]),hostAction:{kind:'document.open',mimeTypes:['text/*','application/json','application/zip','application/octet-stream'],maxBytes:524288}},button('refresh',t[3]));
  }else{
   const skill=mode==='preview'?pending:db.skills.find(s=>s.name===selected);
   if(!skill){mode='list';pending=null;selected=null;nodes.push(text('missing',t[14]));footer.push(button('back',t[5]))}
   else{
    if(mode==='detail')selectedVersion=JSON.stringify(skill);
    nodes.push(text('name',skill.name,'title'),text('description',skill.description),text('notice',t[23],'caption'));
    const paths=Object.keys(skill.files);if(resource!=='@instructions'&&!paths.includes(resource))resource='@instructions';
    nodes.push({id:'resource',kind:'choice',label:t[8],value:resource,action:'resource',options:[{value:'@instructions',label:t[9]},...paths.map(path=>({value:path,label:path}))]});
    const content=resource==='@instructions'?skill.body:skill.files[resource];
    for(let offset=0;offset<content.length;){
     let end=Math.min(content.length,offset+16000);
     if(end<content.length&&content.charCodeAt(end-1)>=0xd800&&content.charCodeAt(end-1)<=0xdbff)end--;
     nodes.push(text(`body_${offset}`,content.slice(offset,end)));offset=end;
    }
    footer.push(button('back',t[5]));
    if(mode==='preview'){
     nodes.unshift(text('preview',t[24],'title'));
     if(previous!=='null')nodes.push({id:'replacement',kind:'choice',label:t[21],value:replacement,action:'replace',options:[{value:'no',label:t[19]},{value:'yes',label:t[20]}]});
     footer.push({...button('confirm_import',t[2],'confirm_import',previous==='null'?t[11]:t[12]),enabled:previous==='null'||replacement==='yes'});
    }else{
     footer.push({...button('export',t[6]),hostAction:{kind:'document.save',name:skill.name+'.skill.json',mimeType:'application/json',maxBytes:524288}},button('delete',t[7],'delete',t[13]));
    }
   }
  }
  return {title:t[0],nodes,footer};
 };
}

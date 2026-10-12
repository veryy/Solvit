import type {PluginContext,PluginPageInput,PluginPageView,PageNode,JsonObject,PageHostAction} from '../../plugin-sdk/src/index';
type W={id:string;name:string};type Snapshot={workspaceId:string|null;workspaces:W[];shells:W[];draftInstallAllowed:boolean};
type FileEntry={name:string;path:string;directory:boolean;bytes:number};
type Draft={id:string;name:string;pluginId:string;version:string;sha256:string;permissions:string[]};
type Button=Extract<PageNode,{kind:'button'}>;
const labels={
 zh:['工作区','新建工作区','绑定已有工作区','名称','创建','绑定','解除绑定','目录','返回','刷新','新建文本','相对路径','文本内容（UTF-8，最多 4KB）','预览保存','确认操作','取消','上级目录','上一页','下一页','目录为空','此对话尚未绑定工作区','文件已保存','操作失败：','插件草稿','查看','安装并保持停用','安装会替换同 ID 插件并停用，启用时需另行授权。','所需权限','操作已完成','文件写入','Shell 名称','添加 Shell'],
 en:['Workspace','New workspace','Bind existing','Name','Create','Bind','Unbind','Directory','Back','Refresh','New text file','Relative path','UTF-8 text (maximum 4 KB)','Preview save','Confirm operation','Cancel','Parent','Previous','Next','Empty directory','This chat has no workspace','File saved','Operation failed: ','Plugin drafts','View','Install disabled','Installation replaces the same plugin id and leaves it disabled. Enable and authorize it separately.','Permissions','Operation completed','File write','Shell name','Add Shell'],
 ja:['ワークスペース','新規作成','既存を関連付け','名前','作成','関連付け','解除','ディレクトリ','戻る','更新','新規テキスト','相対パス','UTF-8テキスト（最大4KB）','保存内容を確認','操作を確認','キャンセル','親ディレクトリ','前へ','次へ','空のディレクトリ','会話にワークスペースがありません','保存済み','操作失敗：','プラグイン草稿','表示','無効のままインストール','同じIDを置換し無効にします。有効化と権限設定は別途行ってください。','権限','完了','ファイル保存','Shell名','Shell追加'],
 ko:['작업 공간','새 작업 공간','기존 연결','이름','만들기','연결','연결 해제','디렉터리','뒤로','새로 고침','새 텍스트','상대 경로','UTF-8 텍스트 (최대 4KB)','저장 미리 보기','작업 확인','취소','상위','이전','다음','빈 디렉터리','작업 공간 없음','저장됨','실패: ','플러그인 초안','보기','비활성 설치','같은 ID를 대체하고 비활성 상태로 둡니다. 권한은 별도로 설정하세요.','권한','완료','파일 쓰기','Shell 이름','Shell 추가'],
 es:['Espacio de trabajo','Crear espacio','Vincular existente','Nombre','Crear','Vincular','Desvincular','Directorio','Volver','Actualizar','Nuevo texto','Ruta relativa','Texto UTF-8 (máximo 4 KB)','Revisar guardado','Confirmar','Cancelar','Directorio superior','Anterior','Siguiente','Directorio vacío','Este chat no tiene espacio','Guardado','Error: ','Borradores','Ver','Instalar desactivado','Reemplaza el mismo ID y queda desactivado. Autorízalo por separado.','Permisos','Completado','Escribir archivo','Nombre de Shell','Añadir Shell'],
 fr:['Espace de travail','Créer un espace','Lier un espace','Nom','Créer','Lier','Délier','Répertoire','Retour','Actualiser','Nouveau texte','Chemin relatif','Texte UTF-8 (maximum 4 Ko)','Vérifier la sauvegarde','Confirmer','Annuler','Parent','Précédent','Suivant','Répertoire vide','Aucun espace pour cette conversation','Enregistré','Échec : ','Brouillons','Voir','Installer désactivé','Remplace le même ID et reste désactivé. Autorisez-le séparément.','Autorisations','Terminé','Écrire le fichier','Nom Shell','Ajouter un Shell']
};
export function workspacePage(){
 let mode='home',directory='',offset=0,total=0,files:FileEntry[]=[],names:W[]=[],workspaceOffset=0,path='',textValue='',sha:string|null=null,originalPath='',message='';
 let pending:Extract<PageHostAction,{kind:'workspace.perform'}>|null=null,returnMode='home',drafts:Draft[]=[],selected:Draft|null=null,draftOffset=0,draftTotal=0,draftText='';
 return async({locale,event}:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const t=labels[locale.split(/[-_]/)[0] as keyof typeof labels]??labels.en;
  const api=ctx.workspacePage;if(!api)throw Error('Workspace page needs API12');
  let snapshot=await api.query('snapshot') as unknown as Snapshot;
  const refresh=async()=>{snapshot=await api.query('snapshot') as unknown as Snapshot};
  const list=async()=>{const result=await api.query('list',{path:directory,offset}) as unknown as {files:FileEntry[];total:number};files=result.files;total=result.total};
  const loadDrafts=async()=>{drafts=await api.query('drafts') as unknown as Draft[]};
  const loadDraft=async()=>{if(!selected)return;const result=await api.query('draft',{id:selected.id,offset:draftOffset}) as unknown as {text:string;total:number;sha256:string};if(result.sha256!==selected.sha256)throw Error('Draft changed');draftText=result.text;draftTotal=result.total};
  const prepare=(operation:Extract<PageHostAction,{kind:'workspace.perform'}>['operation'],args:JsonObject)=>{
   if(operation==='create'||operation==='createShell'){const name=String(args.name);if(!name.trim()||name.length>80||/[\u0000-\u001f\u007f]/.test(name))throw Error('Invalid name')}
   if(operation==='write'){
    const target=String(args.path),content=String(args.text);
    const bytes=Array.from(content).reduce((n,c)=>n+((c.codePointAt(0)??0)>65535?4:(c.codePointAt(0)??0)>2047?3:(c.codePointAt(0)??0)>127?2:1),0);
    if(!target||target.length>1024||target.startsWith('/')||target.split('/').includes('..')||content.includes('\u0000')||bytes>4096)throw Error('Invalid path or text exceeds 4 KB');
   }
   returnMode=mode;pending={kind:'workspace.perform',operation,arguments:args};mode='preview';
  };
  if(event){
   message='';try{
    const action=event.action;
    if(action==='home'){mode='home';pending=null}
    else if(action==='create'){mode='create'}
    else if(action==='prepareCreate')prepare('create',{name:(event.fields.name??'').trim()});
    else if(action==='choose'){mode='choose';workspaceOffset=0}
    else if(action==='choosePrev'){workspaceOffset=Math.max(0,workspaceOffset-40)}
    else if(action==='chooseNext'){workspaceOffset+=40}
    else if(action==='prepareBind'){const value=names[Number(event.nodeId.slice(5))];if(!value)throw Error('Workspace changed');prepare('bind',{id:value.id})}
    else if(action==='prepareUnbind')prepare('unbind',{});
    else if(action==='shell'){mode='shell'}
    else if(action==='prepareShell')prepare('createShell',{name:(event.fields.name??'').trim()});
    else if(action==='directory'){mode='list';directory='';offset=0;await list()}
    else if(action==='refresh'){if(mode==='list')await list();else if(mode==='drafts')await loadDrafts()}
    else if(action==='parent'){directory=directory.includes('/')?directory.slice(0,directory.lastIndexOf('/')):'';offset=0;await list()}
    else if(action==='prev'){offset=Math.max(0,offset-40);await list()}
    else if(action==='next'){offset+=40;await list()}
    else if(action==='file'){
     const file=files[Number(event.nodeId.slice(5))];if(!file)throw Error('Directory changed');
     if(file.directory){directory=file.path;offset=0;await list()}else{
      const result=await api.query('read',{path:file.path}) as unknown as {text:string;sha256:string};
      path=originalPath=file.path;textValue=result.text;sha=result.sha256;mode='editor';
     }
    }else if(action==='new'){path=directory?directory+'/notes.txt':'notes.txt';originalPath=path;textValue='';sha=null;mode='editor'}
    else if(action==='prepareSave'){
     path=(event.fields.path??path).trim();textValue=event.fields.content??textValue;
     prepare('write',{path,text:textValue,expectedSha256:path===originalPath?sha:null});
    }else if(action==='confirm'&&mode==='preview'&&pending){
     snapshot=await api.perform() as unknown as Snapshot;const operation=pending.operation;pending=null;message=t[28];
     mode=operation==='write'?'list':operation==='installDraft'?'drafts':'home';
     if(mode==='list')await list();if(mode==='drafts')await loadDrafts();
    }else if(action==='cancel'){mode=returnMode;pending=null}
    else if(action==='backToList'){mode='list';await list()}
    else if(action==='drafts'){mode='drafts';await loadDrafts()}
    else if(action==='draft'){selected=drafts[Number(event.nodeId.slice(6))];if(!selected)throw Error('Draft changed');draftOffset=0;await loadDraft();mode='draft'}
    else if(action==='draftPrev'){draftOffset=Math.max(0,draftOffset-16000);await loadDraft()}
    else if(action==='draftNext'){draftOffset+=16000;await loadDraft()}
    else if(action==='prepareInstall'&&selected)prepare('installDraft',{id:selected.id,sha256:selected.sha256});
    await refresh();
   }catch(error){message=t[22]+(error instanceof Error?error.message:String(error))}
  }
  const nodes:PageNode[]=[];const footer:Button[]=[];
  const text=(id:string,value:string,tone:'body'|'title'|'caption'='body'):PageNode=>({id,kind:'text',text:value,tone});
  const button=(id:string,label:string,action=id):Button=>({id,kind:'button',text:label,action});
  if(message)nodes.push(text('message',Array.from(message).slice(0,1000).join('')));
  const current=snapshot.workspaces.find(w=>w.id===snapshot.workspaceId);
  if(mode==='home'){
   nodes.push(text('name',current?.name??t[20],'title'));
   nodes.push(button('create',t[1]),button('choose',t[2]));
   if(current){nodes.push(button('directory',t[7]),button('shell',t[31]),text('shells',snapshot.shells.map(s=>s.name).join(' · ')),button('drafts',t[23]),button('prepareUnbind',t[6]));}
  }else if(mode==='create'||mode==='shell'){
   nodes.push({id:'name',kind:'field',label:mode==='create'?t[3]:t[30],value:mode==='create'?'':`Shell ${snapshot.shells.length+1}`});
   footer.push(button('home',t[8]),button(mode==='create'?'prepareCreate':'prepareShell',t[4]));
  }else if(mode==='choose'){
   names=snapshot.workspaces.slice(workspaceOffset,workspaceOffset+40);names.forEach((w,i)=>nodes.push(button(`bind_${i}`,w.name,'prepareBind')));
   footer.push(button('home',t[8]));if(workspaceOffset>0)footer.push(button('choosePrev',t[17]));if(workspaceOffset+40<snapshot.workspaces.length)footer.push(button('chooseNext',t[18]));
  }else if(mode==='list'){
   nodes.push(text('directory',directory||'/','title'));if(!files.length)nodes.push(text('empty',t[19]));
   files.forEach((f,i)=>nodes.push({id:`entry_${i}`,kind:'card',children:[text(`file_name_${i}`,(f.directory?'▸ ':'')+f.name),text(`file_size_${i}`,f.directory?t[7]:`${f.bytes} B`,'caption'),button(`file_${i}`,t[24],'file')]}));
   footer.push(button('home',t[8]),button('new',t[10]),button('refresh',t[9]));if(directory)footer.push(button('parent',t[16]));if(offset>0)footer.push(button('prev',t[17]));if(offset+40<total)footer.push(button('next',t[18]));
  }else if(mode==='editor'){
   nodes.push({id:'path',kind:'field',label:t[11],value:path},{id:'content',kind:'field',label:t[12],value:textValue,multiline:true});
   footer.push(button('backToList',t[8]),button('prepareSave',t[13]));
  }else if(mode==='preview'&&pending){
   nodes.push(text('preview',t[14],'title'));
   if(pending.operation==='write'){nodes.push(text('target',String(pending.arguments.path)),text('contents',String(pending.arguments.text)))}
   else nodes.push(text('operation',JSON.stringify(pending.arguments,null,2)));
   if(pending.operation==='installDraft')nodes.push(text('warning',t[26]));
   footer.push(button('cancel',t[15]),{...button('confirm',t[14]),hostAction:pending});
  }else if(mode==='drafts'){
   drafts.forEach((d,i)=>nodes.push({id:`draft_entry_${i}`,kind:'card',children:[text(`draft_name_${i}`,d.name,'title'),text(`draft_id_${i}`,`${d.pluginId} · ${d.version}`,'caption'),button(`draft_${i}`,t[24],'draft')]}));
   footer.push(button('home',t[8]),button('refresh',t[9]));
  }else if(mode==='draft'&&selected){
   nodes.push(text('draft_name',selected.name,'title'),text('draft_sha',selected.sha256,'caption'),text('permissions',t[27]+': '+selected.permissions.join(', ')),text('source',draftText));
   footer.push(button('drafts',t[8]),{...button('prepareInstall',t[25]),enabled:snapshot.draftInstallAllowed});if(draftOffset>0)footer.push(button('draftPrev',t[17]));if(draftOffset+16000<draftTotal)footer.push(button('draftNext',t[18]));
  }
  return {title:t[0],nodes,footer};
 };
}

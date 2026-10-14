import type {PluginContext,PluginPageInput,PluginPageView,PageNode,JsonObject,PageHostAction} from '../../plugin-sdk/src/index';
type W={id:string;name:string};type Snapshot={workspaceId:string|null;workspaces:W[];shells:W[];draftInstallAllowed:boolean;lastImport?:{path:string;sha256:string;backupPath:string|null}};
type FileEntry={name:string;path:string;directory:boolean;bytes:number};
type Draft={id:string;name:string;pluginId:string;version:string;sha256:string;permissions:string[]};
type Button=Extract<PageNode,{kind:'button'}>;
type TextChunk={text:string;offset:number;nextOffset:number;total:number;sha256:string};
type ImportSelection={id:string;name:string;mimeType:string;bytes:number;sha256:string;source?:{kind:string;messageId:string;attachmentId:string}};
type ImportReview={reviewId:string;path:string;target:{bytes:number;sha256:string}|null};
const replacementLabels={
 zh:['目标文件已存在','确认替换并保留原文件','保存时会再次核对版本；原文件将保留为同目录的 solvit-backup-* 文件。','原文件备份','目标尚不存在，将新建文件。'],
 en:['Destination exists','Replace and keep original','The version is checked again on save. The original stays beside it as solvit-backup-*.','Original backup','Destination is absent. A new file will be created.'],
 ja:['保存先にファイルがあります','元ファイルを残して置換','保存時に再度バージョンを確認。元ファイルは同じフォルダに solvit-backup-* として保持します。','元ファイルのバックアップ','保存先は未作成です。新規ファイルを作成します。'],
 ko:['대상 파일이 있습니다','원본을 보관하고 교체','저장할 때 버전을 다시 확인합니다. 원본은 같은 폴더의 solvit-backup-*에 보관됩니다.','원본 백업','대상이 없습니다. 새 파일을 만듭니다.'],
 es:['El destino existe','Reemplazar y conservar original','Se vuelve a comprobar la versión al guardar. El original queda al lado como solvit-backup-*.','Copia del original','El destino no existe. Se creará un archivo.'],
 fr:['Le fichier cible existe','Remplacer et garder l’original','La version est revérifiée à l’enregistrement. L’original reste à côté sous solvit-backup-*.','Copie de l’original','La cible est absente. Un fichier sera créé.']
};
type ChatFile={messageId:string;messageNumber:number;attachmentId:string;name:string;mimeType:string;bytes:number;sha256:string};
const chatFileLabels={
 zh:['从聊天附件导入','已保存的聊天附件','此对话还没有已保存附件','选择','来源消息'],
 en:['Import chat attachment','Saved chat attachments','No saved attachments in this chat','Select','Source message'],
 ja:['チャット添付をインポート','保存済み添付','この会話に保存済み添付はありません','選択','元メッセージ'],
 ko:['채팅 첨부 가져오기','저장된 채팅 첨부','저장된 첨부가 없습니다','선택','출처 메시지'],
 es:['Importar adjunto del chat','Adjuntos guardados','No hay adjuntos guardados','Elegir','Mensaje de origen'],
 fr:['Importer une pièce jointe du chat','Pièces jointes enregistrées','Aucune pièce jointe enregistrée','Choisir','Message source']
};
const previewLabels={
 zh:['预览文本 / 代码','仅显示开头，导入仍保留完整文件。','此文件不是有效 UTF-8 文本，请使用对应格式预览。','文本预览上限256KiB，仍可导入原文件。'],
 en:['Preview text / code','Only the beginning is shown. Import keeps the complete file.','This is not valid UTF-8 text. Use a preview for its format.','Text previews support up to256KiB. Original file import remains available.'],
 ja:['テキスト / コードを確認','先頭のみ表示。インポートは元のファイル全体を保持します。','有効なUTF-8テキストではありません。形式に対応するプレビューを使用してください。','テキスト表示は最大256KiB。元のファイルはインポートできます。'],
 ko:['텍스트 / 코드 미리 보기','앞부분만 표시합니다. 전체 원본을 가져옵니다.','유효한 UTF-8 텍스트가 아닙니다. 해당 형식의 미리 보기를 사용하세요.','텍스트 미리 보기는 최대256KiB. 원본은 가져올 수 있습니다.'],
 es:['Vista previa de texto / código','Solo se muestra el principio. Se importa el archivo completo.','No es texto UTF-8 válido. Usa una vista previa para su formato.','Vista previa hasta256KiB. Puedes importar el original.'],
 fr:['Aperçu du texte / code','Seul le début est affiché. Le fichier entier sera importé.','Ce fichier n’est pas un texte UTF-8 valide. Utilisez un aperçu de son format.','Aperçu limité à256KiB. L’import du fichier original reste possible.']
};
const pictureLabels={zh:['预览图片','缩略图；透明区域显示白底，动图仅显示首帧。导入保留原文件。'],en:['Preview image','Thumbnail; transparency is white and animation shows its first frame. Import keeps the original.'],ja:['画像を確認','縮小表示。透明部分は白、動画画像は最初のフレーム。元ファイルを保持します。'],ko:['이미지 미리 보기','축소 이미지. 투명 영역은 흰색, 애니메이션은 첫 프레임입니다. 원본을 유지합니다.'],es:['Vista previa de imagen','Miniatura; transparencia blanca y primer fotograma. Se conserva el original.'],fr:['Aperçu de l’image','Miniature ; transparence blanche et première image de l’animation. L’original est conservé.']};
type PdfPreview={page:number;pageCount:number;text:string;truncated:boolean;width:number;height:number;source:string};
const pdfLabels={
 zh:['预览 PDF','页码','查看页面','上一页','下一页','此页没有可提取文字，请查看原页；聊天附件可以使用 OCR。','仅显示此页文字开头，导入保留完整 PDF。','PDF 原页'],
 en:['Preview PDF','Page number','View page','Previous page','Next page','No extractable text on this page. View the original; chat attachments support OCR.','Only the beginning of this page text is shown. Import keeps the complete PDF.','Original PDF page'],
 ja:['PDF を確認','ページ番号','ページを表示','前のページ','次のページ','このページに抽出できる文字はありません。原ページを確認してください。チャット添付はOCRに対応しています。','このページの文字の先頭のみ表示。元のPDF全体を保持します。','PDF 原ページ'],
 ko:['PDF 미리 보기','페이지 번호','페이지 보기','이전 페이지','다음 페이지','추출할 텍스트가 없습니다. 원본 페이지를 보세요. 채팅 첨부는 OCR을 지원합니다.','페이지 텍스트의 앞부분만 표시합니다. 전체 PDF를 가져옵니다.','PDF 원본 페이지'],
 es:['Vista previa de PDF','Número de página','Ver página','Página anterior','Página siguiente','Sin texto extraíble. Consulta el original; los adjuntos de chat admiten OCR.','Solo se muestra el principio del texto de esta página. Se importa el PDF completo.','Página PDF original'],
 fr:['Aperçu PDF','Numéro de page','Afficher la page','Page précédente','Page suivante','Aucun texte extractible. Consultez la page originale ; les pièces jointes du chat prennent en charge l’OCR.','Seul le début du texte de cette page est affiché. Le PDF complet est conservé.','Page PDF originale']
};
const importLabels={
 zh:['导入文件','目标路径','确认导入','放弃选择','最多20MiB，保留原始文件；同名文件需先审阅再确认替换。','重新选择','来源：系统文件选择器'],
 en:['Import file','Destination path','Confirm import','Discard selection','Up to 20MiB, original bytes. Review an existing file before confirming replacement.','Choose another','Source: system file picker'],
 ja:['ファイルをインポート','保存先パス','インポートを確認','選択を破棄','最大20MiB、元のバイトを保持。同名ファイルは確認後に置換します。','再選択','ソース：システムファイル選択'],
 ko:['파일 가져오기','대상 경로','가져오기 확인','선택 취소','최대20MiB, 원본 유지. 기존 파일은 확인 후 교체합니다.','다시 선택','출처: 시스템 파일 선택'],
 es:['Importar archivo','Ruta de destino','Confirmar importación','Descartar selección','Hasta20MiB, bytes originales. Revisa el destino antes de reemplazarlo.','Elegir otro','Origen: selector de archivos'],
 fr:['Importer un fichier','Chemin cible','Confirmer l’import','Abandonner la sélection','20MiB maximum, octets originaux. Vérifiez la cible avant de la remplacer.','Choisir un autre','Source : sélecteur système']
};
const fileLabels={
 zh:['新建目录','重命名 / 移动','删除','目标路径','目录及其中内容将被删除，无法撤销。'],
 en:['New folder','Rename / move','Delete','Destination path','The folder and its contents will be deleted permanently.'],
 ja:['新規フォルダ','名前変更 / 移動','削除','移動先パス','フォルダと内容を完全に削除します。'],
 ko:['새 폴더','이름 변경 / 이동','삭제','대상 경로','폴더와 내용을 영구 삭제합니다.'],
 es:['Nueva carpeta','Renombrar / mover','Eliminar','Ruta de destino','Se eliminarán permanentemente la carpeta y su contenido.'],
 fr:['Nouveau dossier','Renommer / déplacer','Supprimer','Chemin cible','Le dossier et son contenu seront supprimés définitivement.']
};
const labels={
 zh:['工作区','新建工作区','绑定已有工作区','名称','创建','绑定','解除绑定','目录','返回','刷新','新建文本','相对路径','文本内容（UTF-8，最多 4KB）','预览保存','确认操作','取消','上级目录','上一页','下一页','目录为空','此对话尚未绑定工作区','文件已保存','操作失败：','插件草稿','查看','安装并保持停用','安装会替换同 ID 插件并停用，启用时需另行授权。','所需权限','操作已完成','文件写入','Shell 名称','添加 Shell'],
 en:['Workspace','New workspace','Bind existing','Name','Create','Bind','Unbind','Directory','Back','Refresh','New text file','Relative path','UTF-8 text (maximum 4 KB)','Preview save','Confirm operation','Cancel','Parent','Previous','Next','Empty directory','This chat has no workspace','File saved','Operation failed: ','Plugin drafts','View','Install disabled','Installation replaces the same plugin id and leaves it disabled. Enable and authorize it separately.','Permissions','Operation completed','File write','Shell name','Add Shell'],
 ja:['ワークスペース','新規作成','既存を関連付け','名前','作成','関連付け','解除','ディレクトリ','戻る','更新','新規テキスト','相対パス','UTF-8テキスト（最大4KB）','保存内容を確認','操作を確認','キャンセル','親ディレクトリ','前へ','次へ','空のディレクトリ','会話にワークスペースがありません','保存済み','操作失敗：','プラグイン草稿','表示','無効のままインストール','同じIDを置換し無効にします。有効化と権限設定は別途行ってください。','権限','完了','ファイル保存','Shell名','Shell追加'],
 ko:['작업 공간','새 작업 공간','기존 연결','이름','만들기','연결','연결 해제','디렉터리','뒤로','새로 고침','새 텍스트','상대 경로','UTF-8 텍스트 (최대 4KB)','저장 미리 보기','작업 확인','취소','상위','이전','다음','빈 디렉터리','작업 공간 없음','저장됨','실패: ','플러그인 초안','보기','비활성 설치','같은 ID를 대체하고 비활성 상태로 둡니다. 권한은 별도로 설정하세요.','권한','완료','파일 쓰기','Shell 이름','Shell 추가'],
 es:['Espacio de trabajo','Crear espacio','Vincular existente','Nombre','Crear','Vincular','Desvincular','Directorio','Volver','Actualizar','Nuevo texto','Ruta relativa','Texto UTF-8 (máximo 4 KB)','Revisar guardado','Confirmar','Cancelar','Directorio superior','Anterior','Siguiente','Directorio vacío','Este chat no tiene espacio','Guardado','Error: ','Borradores','Ver','Instalar desactivado','Reemplaza el mismo ID y queda desactivado. Autorízalo por separado.','Permisos','Completado','Escribir archivo','Nombre de Shell','Añadir Shell'],
 fr:['Espace de travail','Créer un espace','Lier un espace','Nom','Créer','Lier','Délier','Répertoire','Retour','Actualiser','Nouveau texte','Chemin relatif','Texte UTF-8 (maximum 4 Ko)','Vérifier la sauvegarde','Confirmer','Annuler','Parent','Précédent','Suivant','Répertoire vide','Aucun espace pour cette conversation','Enregistré','Échec : ','Brouillons','Voir','Installer désactivé','Remplace le même ID et reste désactivé. Autorisez-le séparément.','Autorisations','Terminé','Écrire le fichier','Nom Shell','Ajouter un Shell']
};
export function workspacePage(importsEnabled=true){
 let mode='home',directory='',offset=0,total=0,files:FileEntry[]=[],names:W[]=[],workspaceOffset=0,path='',textValue='',sha:string|null=null,originalPath='',message='';
 let pending:Extract<PageHostAction,{kind:'workspace.perform'}>|null=null,returnMode='home',drafts:Draft[]=[],selected:Draft|null=null,draftOffset=0,draftTotal=0,draftText='';
 let editorId=0,chunk:TextChunk|null=null,phase='ready',uploadId='',uploadOffset=0;
 let managementPath='';
 let importSelection:ImportSelection|null=null,importPath='';
 let importReview:ImportReview|null=null;
 let importPreview:{kind:string;text:string|null;truncated:boolean}|null=null;
 let importPicture:{width:number;height:number;source:string}|null=null;
 let importPdf:PdfPreview|null=null;
 let chatFiles:ChatFile[]=[],chatFileOffset=0,chatFileTotal=0,chatReturnMode='home';
 let treeWorkspace:string|null=null,treeResult:JsonObject|null=null,treeRevision=0;
 const treeEntries=new Map<string,FileEntry>();
 return async({locale,event}:PluginPageInput,ctx:PluginContext):Promise<PluginPageView>=>{
  const t=labels[locale.split(/[-_]/)[0] as keyof typeof labels]??labels.en;
  const f=fileLabels[locale.split(/[-_]/)[0] as keyof typeof fileLabels]??fileLabels.en;
  const im=importLabels[locale.split(/[-_]/)[0] as keyof typeof importLabels]??importLabels.en;
  const ir=replacementLabels[locale.split(/[-_]/)[0] as keyof typeof replacementLabels]??replacementLabels.en;
  const ip=previewLabels[locale.split(/[-_]/)[0] as keyof typeof previewLabels]??previewLabels.en;
  const pic=pictureLabels[locale.split(/[-_]/)[0] as keyof typeof pictureLabels]??pictureLabels.en;
  const pdf=pdfLabels[locale.split(/[-_]/)[0] as keyof typeof pdfLabels]??pdfLabels.en;
  const cf=chatFileLabels[locale.split(/[-_]/)[0] as keyof typeof chatFileLabels]??chatFileLabels.en;
  const api=ctx.workspacePage;if(!api)throw Error('Workspace page needs API12');
  let snapshot=await api.query('snapshot') as unknown as Snapshot;
  if(treeWorkspace!==snapshot.workspaceId){treeWorkspace=snapshot.workspaceId;treeResult=null;treeEntries.clear()}
  const refresh=async()=>{snapshot=await api.query('snapshot') as unknown as Snapshot};
  const list=async()=>{const result=await api.query('list',{path:directory,offset}) as unknown as {files:FileEntry[];total:number};files=result.files;total=result.total};
  const loadChatFiles=async()=>{const page=await api.query('chatAttachments',{offset:chatFileOffset}) as unknown as {attachments:ChatFile[];total:number};chatFiles=page.attachments;chatFileTotal=page.total};
  const loadDrafts=async()=>{drafts=await api.query('drafts') as unknown as Draft[]};
  const loadDraft=async()=>{if(!selected)return;const result=await api.query('draft',{id:selected.id,offset:draftOffset}) as unknown as {text:string;total:number;sha256:string};if(result.sha256!==selected.sha256)throw Error('Draft changed');draftText=result.text;draftTotal=result.total};
  const readChunk=async(offset:number)=>{
   const next=await api.query('readChunk',{path,offset,expectedSha256:sha}) as unknown as TextChunk;
   if(next.offset!==offset || next.nextOffset<offset || next.nextOffset>next.total ||
      next.text.length!==next.nextOffset-offset || sha!==null&&next.sha256!==sha)throw Error('File preview changed');
   chunk=next;sha=next.sha256;phase=next.nextOffset===next.total?'ready':'reading';
  };
  const prepare=(operation:Extract<PageHostAction,{kind:'workspace.perform'}>['operation'],args:JsonObject)=>{
   if(operation==='create'||operation==='createShell'){const name=String(args.name);if(!name.trim()||name.length>80||/[\u0000-\u001f\u007f]/.test(name))throw Error('Invalid name')}
   if(operation==='write'){
    const target=String(args.path),content=String(args.text);
    const bytes=Array.from(content).reduce((n,c)=>n+((c.codePointAt(0)??0)>65535?4:(c.codePointAt(0)??0)>2047?3:(c.codePointAt(0)??0)>127?2:1),0);
    if(!target||target.length>1024||target.startsWith('/')||target.split('/').includes('..')||content.includes('\u0000')||bytes>4096)throw Error('Invalid path or text exceeds 4 KB');
   }
   if(['makeDirectory','moveEntry','deleteEntry'].includes(operation)){
    for(const key of operation==='moveEntry'?['path','destination']:['path']){
     const value=String(args[key]);
     if(!value||value.length>1024||value.startsWith('/')||value.includes('\\')||/[\u0000-\u001f\u007f]/.test(value)||value.split('/').some(p=>!p||p==='.'||p==='..'))throw Error('Invalid relative path');
    }
   }
   returnMode=mode;pending={kind:'workspace.perform',operation,arguments:args};mode='preview';
  };
  if(event){
   // Passive tree fetches follow writes automatically; keep their result/backup visible.
   if(event.action!=='treeLoad')message='';try{
    const action=event.action;
    if(action==='chatFiles'){chatReturnMode=mode;chatFileOffset=0;await loadChatFiles();mode='chatFiles'}
    else if(action==='chatFilesPrev'||action==='chatFilesNext'||action==='chatFilesRefresh'){chatFileOffset=action==='chatFilesRefresh'?0:action==='chatFilesPrev'?Math.max(0,chatFileOffset-20):chatFileOffset+20;await loadChatFiles()}
    else if(action==='chatFilesBack'){mode=chatReturnMode}
    else if(action==='selectChatAttachment'){
     await api.perform();
     const chosen=await api.query('importSelection') as unknown as ImportSelection|null;
     if(!chosen||chosen.source?.kind!=='chatAttachment')throw Error('Chat attachment selection failed');
     importSelection=chosen;importPreview=null;importPicture=null;importPdf=null;importPath=(directory?directory+'/':'')+chosen.name;mode='import';
    }
    else if(action==='selectImport'){
     const chosen=await api.perform() as unknown as ImportSelection|null;
     // Cancellation keeps the previous selection; only the host can supply metadata.
     if(chosen){importSelection=chosen;importPreview=null;importPicture=null;importPdf=null;importPath=(directory?directory+'/':'')+chosen.name;mode='import'}
    }
    else if(action==='importPreview'&&importSelection){importPath=event.fields.importPath??importPath;importPreview=await api.query('importText',{id:importSelection.id,sha256:importSelection.sha256}) as unknown as {kind:string;text:string|null;truncated:boolean}}
    else if(action==='importPicture'&&importSelection){importPath=event.fields.importPath??importPath;importPicture=await api.query('importPicture',{id:importSelection.id,sha256:importSelection.sha256}) as unknown as {width:number;height:number;source:string}}
    else if(['importPdf','pdfPrev','pdfNext','pdfGo'].includes(action)&&importSelection){
     importPath=event.fields.importPath??importPath;
     const page=action==='pdfPrev'?(importPdf?.page??1)-1:action==='pdfNext'?(importPdf?.page??1)+1:action==='pdfGo'?Number(event.fields.pdfPageNumber):1;
     if(!Number.isInteger(page)||page<1||page>5000||importPdf&&page>importPdf.pageCount)throw Error('Invalid PDF page');
     const next=await api.query('importPdf',{id:importSelection.id,sha256:importSelection.sha256,page}) as unknown as PdfPreview;
     if(next.page!==page||!Number.isInteger(next.pageCount)||next.pageCount<page||next.pageCount>5000)throw Error('PDF page changed');
     importPdf=next;
    }
    else if(action==='showImport'){importSelection=await api.query('importSelection') as unknown as ImportSelection|null;if(importSelection)mode='import'}
    else if(action==='prepareImport'&&importSelection){
     importPath=(event.fields.importPath??importPath).trim();
     if(!importPath||importPath.length>1024||importPath.startsWith('/')||importPath.includes('\\')||/[\u0000-\u001f\u007f]/.test(importPath)||importPath.split('/').some(p=>!p||p==='.'||p==='..'||p==='.git'))throw Error('Invalid relative path');
     importReview=await api.query('reviewImport',{id:importSelection.id,path:importPath,sha256:importSelection.sha256}) as unknown as ImportReview;
     if(importReview.path!==importPath||!importReview.reviewId)throw Error('Import review changed');
     prepare('importReviewed',{id:importSelection.id,path:importPath,sha256:importSelection.sha256,reviewId:importReview.reviewId});
    }
    else if(action==='dropImport'&&importSelection){await api.perform();importSelection=null;importPreview=null;importPicture=null;importPdf=null;mode='list';await list()}
    else if(action==='treeLoad'){
     const target=event.fields.treePath??'',start=Number(event.fields.treeOffset??0);
     if(!Number.isInteger(start)||start<0||start>1000)throw Error('Invalid directory offset');
     const result=await api.query('list',{path:target,offset:start}) as unknown as {files:FileEntry[];total:number};
     if(start>result.total)throw Error('Directory changed. Refresh it.');
     const entries=result.files.slice(0,10);entries.forEach(entry=>treeEntries.set(entry.path,entry));
     treeResult={path:target,offset:start,nextOffset:start+entries.length,total:result.total,entries:entries as unknown as JsonObject[]};
    }
    else if(action==='treeOpen'){
     const entry=treeEntries.get(event.fields.treeTarget??'');if(!entry)throw Error('Refresh the file tree first');
     if(entry.directory){directory=entry.path;offset=0;await list();mode='list'}
     else{path=originalPath=entry.path;sha=null;editorId++;await readChunk(0);mode='editor'}
    }
    else if(action==='home'){mode='home';pending=null}
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
    else if(action==='refresh'){if(mode==='list'){await list();treeRevision++;treeResult=null;treeEntries.clear()}else if(mode==='drafts')await loadDrafts()}
    else if(action==='parent'){directory=directory.includes('/')?directory.slice(0,directory.lastIndexOf('/')):'';offset=0;await list()}
    else if(action==='prev'){offset=Math.max(0,offset-40);await list()}
    else if(action==='next'){offset+=40;await list()}
    else if(action==='mkdir'){mode='mkdir';managementPath=directory?directory+'/new-folder':'new-folder'}
    else if(action==='prepareDirectory')prepare('makeDirectory',{path:(event.fields.destination??'').trim()});
    else if(action==='renameEntry'||action==='deleteEntry'){
     const entry=files[Number(event.nodeId.split('_')[1])];if(!entry)throw Error('Directory changed');
     managementPath=entry.path;
     if(action==='renameEntry')mode='rename';else prepare('deleteEntry',{path:entry.path});
    }
    else if(action==='prepareMove')prepare('moveEntry',{path:managementPath,destination:(event.fields.destination??'').trim()});
    else if(action==='file'){
     const file=files[Number(event.nodeId.slice(5))];if(!file)throw Error('Directory changed');
     if(file.directory){directory=file.path;offset=0;await list()}else{
      path=originalPath=file.path;sha=null;editorId++;await readChunk(0);mode='editor';
     }
    }else if(action==='new'){
     path=directory?directory+'/notes.txt':'notes.txt';originalPath=path;textValue='';sha=null;editorId++;
     chunk={text:'',offset:0,nextOffset:0,total:0,sha256:''};phase='ready';mode='editor';
    }
    else if(action==='readNext'&&mode==='editor'&&phase==='reading'&&chunk)await readChunk(chunk.nextOffset);
    else if(action==='retryRead'&&mode==='editor'&&phase==='readFailed'&&chunk){phase='reading';await readChunk(chunk.nextOffset)}
    else if(action==='prepareSave'){
     if(mode!=='editor'||phase!=='ready')throw Error('Wait for the complete file');
     path=(event.fields.path??path).trim();
     const length=Number(event.fields.uploadTotal);
     if(!Number.isInteger(length)||length<0||length>262144)throw Error('Invalid document size');
     const draft=await api.query('editBegin',{path,expectedSha256:path===originalPath?sha:null,total:length}) as unknown as {draftId:string};
     uploadId=draft.draftId;uploadOffset=0;phase='uploading';
    }else if(action==='uploadChunk'&&mode==='editor'&&phase==='uploading'){
     const next=await api.query('editAppend',{draftId:uploadId,offset:Number(event.fields.uploadOffset),text:event.fields.content??''}) as unknown as {offset:number};
     uploadOffset=next.offset;
    }else if(action==='finishUpload'&&mode==='editor'&&phase==='uploading'){
     const draft=await api.query('editFinish',{draftId:uploadId}) as unknown as {draftId:string;path:string;sha256:string};
     prepare('writeDraft',{draftId:draft.draftId,path:draft.path,sha256:draft.sha256});phase='review';
    }else if(action==='confirm'&&mode==='preview'&&pending){
     snapshot=await api.perform() as unknown as Snapshot;const operation=pending.operation;pending=null;message=t[28];
     if(operation==='importReviewed'){importSelection=null;importPreview=null;importPicture=null;importPdf=null;importReview=null;if(snapshot.lastImport?.backupPath)message+=` · ${ir[3]}: ${snapshot.lastImport.backupPath}`}
     if(['write','writeDraft','makeDirectory','moveEntry','deleteEntry','importReviewed'].includes(operation)){treeRevision++;treeResult=null;treeEntries.clear()}
     mode=['write','writeDraft','makeDirectory','moveEntry','deleteEntry','importReviewed'].includes(operation)?'list':operation==='installDraft'?'drafts':'home';
     if(mode==='list')await list();if(mode==='drafts')await loadDrafts();
    }else if(action==='cancel'){
     if(pending?.operation==='writeDraft'){await api.query('editDiscard',{draftId:uploadId});uploadId='';phase='ready'}
     mode=returnMode;pending=null;
    }
    else if(action==='cancelUpload'&&mode==='editor'&&(phase==='uploading'||phase==='uploadFailed')){
     await api.query('editDiscard',{draftId:uploadId});uploadId='';phase='ready';
    }
    else if(action==='backToList'){mode='list';await list()}
    else if(action==='drafts'){mode='drafts';await loadDrafts()}
    else if(action==='draft'){selected=drafts[Number(event.nodeId.slice(6))];if(!selected)throw Error('Draft changed');draftOffset=0;await loadDraft();mode='draft'}
    else if(action==='draftPrev'){draftOffset=Math.max(0,draftOffset-16000);await loadDraft()}
    else if(action==='draftNext'){draftOffset+=16000;await loadDraft()}
    else if(action==='prepareInstall'&&selected)prepare('installDraft',{id:selected.id,sha256:selected.sha256});
    await refresh();
   }catch(error){
    if(pending?.operation==='importReviewed'){pending=null;importReview=null;mode='import'}
    if(mode==='editor'&&phase==='reading')phase='readFailed';
    else if(mode==='editor'&&phase==='uploading')phase='uploadFailed';
    message=t[22]+(error instanceof Error?error.message:String(error));
   }
  }
  const nodes:PageNode[]=[];const footer:Button[]=[];
  const text=(id:string,value:string,tone:'body'|'title'|'caption'='body'):PageNode=>({id,kind:'text',text:value,tone});
  const button=(id:string,label:string,action=id):Button=>({id,kind:'button',text:label,action});
  const fromChat=():Button=>button('chatFiles',cf[0]);
  const selectImport=():Button=>({...button('selectImport',importSelection?im[5]:im[0]),hostAction:{kind:'workspace.perform',operation:'selectImport',arguments:{}}});
  if(message)nodes.push(text('message',Array.from(message).slice(0,1000).join('')));
  const current=snapshot.workspaces.find(w=>w.id===snapshot.workspaceId);
  nodes.push(text('tree_meta',JSON.stringify({workspaceId:snapshot.workspaceId,revision:treeRevision,result:treeResult}),'caption'),
   {id:'treePath',kind:'field',label:'Directory',value:''},
   {id:'treeOffset',kind:'field',label:'Offset',value:'0'},
   {id:'treeTarget',kind:'field',label:'File',value:''},
   button('tree_load','Load directory','treeLoad'),button('tree_open','Open file','treeOpen'));
  if(mode==='home'){
   nodes.push(text('name',current?.name??t[20],'title'));
   nodes.push(button('create',t[1]),button('choose',t[2]));
   if(current){nodes.push(button('directory',t[7]),...(importsEnabled?[selectImport(),fromChat()]:[]),button('shell',t[31]),text('shells',snapshot.shells.map(s=>s.name).join(' · ')),button('drafts',t[23]),button('prepareUnbind',t[6]));if(importSelection)nodes.push(button('showImport',im[2]))}
  }else if(mode==='create'||mode==='shell'){
   nodes.push({id:'name',kind:'field',label:mode==='create'?t[3]:t[30],value:mode==='create'?'':`Shell ${snapshot.shells.length+1}`});
   footer.push(button('home',t[8]),button(mode==='create'?'prepareCreate':'prepareShell',t[4]));
  }else if(mode==='choose'){
   names=snapshot.workspaces.slice(workspaceOffset,workspaceOffset+40);names.forEach((w,i)=>nodes.push(button(`bind_${i}`,w.name,'prepareBind')));
   footer.push(button('home',t[8]));if(workspaceOffset>0)footer.push(button('choosePrev',t[17]));if(workspaceOffset+40<snapshot.workspaces.length)footer.push(button('chooseNext',t[18]));
  }else if(mode==='list'){
   nodes.push(text('directory',directory||'/','title'),{id:'directory_actions',kind:'row',children:[
    ...(importsEnabled?[selectImport(),fromChat()]:[]),button('new',t[10]),button('mkdir',f[0])]});
   if(!files.length)nodes.push(text('empty',t[19]));
   files.forEach((entry,i)=>nodes.push({id:`entry_${i}`,kind:'card',children:[text(`file_name_${i}`,(entry.directory?'▸ ':'')+entry.name),text(`file_size_${i}`,entry.directory?t[7]:`${entry.bytes} B`,'caption'),button(`file_${i}`,t[24],'file'),
    button(`rename_${i}`,f[1],'renameEntry'),button(`delete_${i}`,f[2],'deleteEntry')]}));
   footer.push(button('home',t[8]),button('refresh',t[9]));if(directory)footer.push(button('parent',t[16]));if(offset>0)footer.push(button('prev',t[17]));if(offset+40<total)footer.push(button('next',t[18]));
  }else if(mode==='chatFiles'){
   nodes.push(text('chat_files_title',cf[1],'title'));if(!chatFiles.length)nodes.push(text('chat_files_empty',cf[2]));
   chatFiles.forEach((entry,i)=>nodes.push({id:`chat_file_${i}`,kind:'card',children:[text(`chat_file_name_${i}`,entry.name),text(`chat_file_info_${i}`,`${entry.mimeType} · ${entry.bytes} B`,'caption'),text(`chat_file_source_${i}`,`${cf[4]} ${entry.messageNumber} · ${entry.messageId}`,'caption'),{...button(`chat_file_select_${i}`,cf[3],'selectChatAttachment'),hostAction:{kind:'workspace.perform',operation:'selectChatAttachment',arguments:{messageId:entry.messageId,attachmentId:entry.attachmentId,sha256:entry.sha256}}}]}));
   footer.push(button('chatFilesBack',t[8]),button('chatFilesRefresh',t[9]));if(chatFileOffset>0)footer.push(button('chatFilesPrev',t[17]));if(chatFileOffset+20<chatFileTotal)footer.push(button('chatFilesNext',t[18]));
  }else if(mode==='import'&&importSelection){
   nodes.push(text('import_name',importSelection.name,'title'),text('import_type',`${importSelection.mimeType} · ${importSelection.bytes} B`),
    text('import_source',importSelection.source?.kind==='chatAttachment'?`${cf[4]} · ${importSelection.source.messageId}`:im[6],'caption'),text('import_sha',importSelection.sha256,'caption'),text('import_limit',im[4]),
    {id:'importPath',kind:'field',label:im[1],value:importPath});
   if(importSelection.mimeType.startsWith('image/'))nodes.push(button('importPicture',pic[0]));
   else if(importSelection.mimeType==='application/pdf')nodes.push(button('importPdf',pdf[0]));
   else nodes.push(button('importPreview',ip[0]));
   if(importPicture)nodes.push({id:'import_picture',kind:'image',source:importPicture.source,alt:importSelection.name,width:importPicture.width,height:importPicture.height},text('import_picture_note',pic[1],'caption'));
   if(importPdf){
    nodes.push(text('pdf_page_meta',`${pdf[7]} ${importPdf.page} / ${importPdf.pageCount}`),
     {id:'import_pdf_picture',kind:'image',source:importPdf.source,alt:`${importSelection.name} ${importPdf.page}`,width:importPdf.width,height:importPdf.height},
     {id:'pdfPageNumber',kind:'field',label:pdf[1],value:String(importPdf.page)},
     {id:'pdf_navigation',kind:'row',children:[{...button('pdfPrev',pdf[3]),enabled:importPdf.page>1},button('pdfGo',pdf[2]),{...button('pdfNext',pdf[4]),enabled:importPdf.page<importPdf.pageCount}]});
    nodes.push(text('pdf_page_text',importPdf.text.trim()?importPdf.text:pdf[5]));
    if(importPdf.truncated)nodes.push(text('pdf_truncated',pdf[6],'caption'));
   }
   if(importPreview){
    if(importPreview.kind==='TEXT'){
     nodes.push(text('import_content',importPreview.text??''));
     if(importPreview.truncated)nodes.push(text('import_truncated',ip[1],'caption'));
    }else nodes.push(text('import_preview_unavailable',importPreview.kind==='OVERSIZED'?ip[3]:ip[2]));
   }
   footer.push(selectImport(),fromChat(),{...button('dropImport',im[3]),hostAction:{kind:'workspace.perform',operation:'dropImport',arguments:{id:importSelection.id}}},button('prepareImport',im[2]));
  }else if(mode==='mkdir'||mode==='rename'){
   nodes.push({id:'destination',kind:'field',label:f[3],value:managementPath});
   footer.push(button('backToList',t[8]),button(mode==='mkdir'?'prepareDirectory':'prepareMove',t[14]));
  }else if(mode==='editor'){
   nodes.push(text('document_meta',JSON.stringify({editorId,path,phase,sha256:sha,offset:chunk?.offset??0,
    nextOffset:chunk?.nextOffset??0,total:chunk?.total??0,draftId:uploadId,uploadOffset}),'caption'),
    text('document_chunk',phase==='reading'||phase==='ready'?chunk?.text??'':''),
    {id:'path',kind:'field',label:t[11],value:path},
    {id:'content',kind:'field',label:'Document chunk',value:'',multiline:true},
    {id:'uploadOffset',kind:'field',label:'Upload offset',value:String(uploadOffset)},
    {id:'uploadTotal',kind:'field',label:'Document length',value:'0'},
    {...button('readNext','Load','readNext'),enabled:phase==='reading'},
    {...button('uploadChunk','Upload','uploadChunk'),enabled:phase==='uploading'},
    {...button('finishUpload','Prepare save','finishUpload'),enabled:phase==='uploading'});
   if(phase==='readFailed')nodes.push(button('retryRead',t[9]));
   footer.push(button('backToList',t[8]),phase==='uploading'||phase==='uploadFailed'?button('cancelUpload',t[15]):
    {...button('prepareSave',t[13]),enabled:phase==='ready'});
  }else if(mode==='preview'&&pending){
   nodes.push(text('preview',t[14],'title'));
   if(pending.operation==='write'){nodes.push(text('target',String(pending.arguments.path)),text('contents',String(pending.arguments.text)))}
   else if(pending.operation==='writeDraft')nodes.push(text('target',String(pending.arguments.path)),text('save_sha',String(pending.arguments.sha256),'caption'));
   else if(pending.operation==='importReviewed'&&importSelection){
    nodes.push(text('import_name',importSelection.name),text('import_type',`${importSelection.mimeType} · ${importSelection.bytes} B`),text('target',String(pending.arguments.path)),text('import_sha',importSelection.sha256,'caption'));
    if(importReview?.target)nodes.push(text('replace_title',ir[0],'title'),text('replace_size',`${importReview.target.bytes} B`),text('replace_sha',importReview.target.sha256,'caption'),text('replace_warning',ir[2]));
    else nodes.push(text('replace_absent',ir[4]));
   }
   else nodes.push(text('operation',JSON.stringify(pending.arguments,null,2)));
   if(pending.operation==='installDraft')nodes.push(text('warning',t[26]));
   if(pending.operation==='deleteEntry')nodes.push(text('warning',f[4]));
   footer.push(button('cancel',t[15]),{...button('confirm',pending.operation==='importReviewed'&&importReview?.target?ir[1]:t[14]),hostAction:pending});
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

import type {PluginContext,PluginPageInput,PluginPageView,PageNode,JsonObject,PageHostAction} from '../../plugin-sdk/src/index';
type W={id:string;name:string};type Snapshot={workspaceId:string|null;workspaces:W[];shells:W[];draftInstallAllowed:boolean;lastImport?:{path:string;sha256:string;backupPath:string|null}};
type FileEntry={name:string;path:string;directory:boolean;bytes:number};
type Draft={id:string;name:string;pluginId:string;version:string;sha256:string;permissions:string[]};
type Button=Extract<PageNode,{kind:'button'}>;
type TextChunk={text:string;offset:number;nextOffset:number;total:number;sha256:string};
type ImportSelection={id:string;name:string;mimeType:string;bytes:number;sha256:string;source?:{kind:string;messageId?:string;attachmentId?:string;backupId?:string;path?:string;backupPath?:string}};
type ImportReview={reviewId:string;path:string;target:{bytes:number;sha256:string}|null};
type ImportOperation={operationId:string;status:string;progress?:{stage:string;completed:number;total:number};result?:Snapshot;error?:string};
type ImportRecovery={id:string;path:string;backupPath:string|null;status:string};
type Backup={id:string;path:string;backupPath:string;originalBytes:number;originalSha256:string;createdAt:number;status:string;currentBytes:number|null;pending:boolean};
type BackupPage={backups:Backup[];offset:number;total:number;logicalBytes:number;availableBytes:number|null;incomplete:boolean;unreadableRecords:number};
type BackupReview={backupId:string;path:string;backupPath:string;bytes:number;sha256:string};
type Cleanup={id:string;path:string;backupPath:string;phase:string;status:string;heldBytes:number|null};
type CleanupPage={cleanups:Cleanup[];offset:number;total:number;incomplete:boolean;unreadableEntries:number};
const cleanupLabels={
 zh:['清理这份备份','将删除刚审阅的备份，当前目标文件不受影响。已有打开文件或硬链接不受锁定。','备份已清理','未完成的清理','没有待处理的清理记录','退回原备份位置','已退回；请重新审阅后再决定是否清理。','结果未确认，不会自动重试删除。','认领文件保留在应用私有区，重启不会自动删除。','原备份仍在原处','文件已暂存，等待处理','认领的文件身份不同，禁止删除','原位置已有文件；两份文件都保留','原路径暂不可用，请先检查工作目录','结果未确认','已确认删除','仅关闭记录','只关闭清理和来源记录，不删除任何文件；不能据此确认先前清理是否成功。','清理记录扫描不完整或不可读','返回备份列表','退回时不会覆盖原位置的同名文件。'],
 en:['Clean this backup','Deletes the reviewed backup; the current target is kept. Existing open descriptors and hardlinks are not locked.','Backup cleaned','Unfinished cleanup','No pending cleanup records','Return to backup location','Returned; review again before deciding to clean.','Outcome unconfirmed; deletion is never retried automatically.','Claimed files stay private and are never deleted automatically on restart.','Original backup is still at its location','File held for review','Claimed file identity differs; deletion blocked','Original name is occupied; both files are kept','Original path unavailable; inspect the workspace first','Outcome unconfirmed','Deletion confirmed','Dismiss record only','Dismisses cleanup and provenance records only, never files; this does not confirm the earlier cleanup outcome.','Cleanup scan incomplete or entries unreadable','Back to backups','Returning never replaces a file at the original name.'],
 ja:['このバックアップを削除','確認したバックアップを削除します。現在の対象ファイルは保持します。既存のFDやハードリンクはロックしません。','削除しました','未完了の削除','未処理の記録はありません','元の場所へ戻す','戻しました。削除する前に再確認してください。','結果未確認。自動で削除を再実行しません。','一時保管したファイルは再起動時に自動削除しません。','元の場所にあります','ファイルを一時保管中','ファイルIDが異なるため削除不可','元の名前が使用中。両方を保持','元のパスを確認してください','結果未確認','削除確認済み','記録のみ閉じる','ファイルは削除しません。以前の削除結果の確認にはなりません。','記録の走査が不完全、または読み取り不可','一覧へ戻る','元の場所の同名ファイルは上書きしません。'],
 ko:['이 백업 정리','검토한 백업을 삭제하며 현재 대상은 유지합니다. 열린 파일 및 하드 링크는 잠그지 않습니다.','백업 정리 완료','미완료 정리','처리할 기록 없음','원래 위치로 반환','반환 완료. 정리하려면 다시 검토하세요.','결과 미확인. 자동으로 삭제를 재시도하지 않습니다.','임시 보관 파일은 재시작 시 자동 삭제하지 않습니다.','원래 위치에 있음','파일 보관 중','파일 식별자가 달라 삭제 차단','원래 이름 사용 중. 두 파일 유지','원래 경로를 확인하세요','결과 미확인','삭제 확인됨','기록만 닫기','파일을 삭제하지 않으며 이전 정리 결과를 확정하지 않습니다.','기록 검색 불완전 또는 읽기 실패','백업 목록으로','원래 위치의 동명 파일을 덮어쓰지 않습니다.'],
 es:['Limpiar esta copia','Elimina la copia revisada; conserva el destino actual. Los descriptores abiertos y enlaces duros no se bloquean.','Copia eliminada','Limpieza pendiente','Sin registros pendientes','Devolver a su ubicación','Devuelta; revísala antes de limpiarla.','Resultado sin confirmar; nunca se repite la eliminación automáticamente.','Los archivos retenidos no se eliminan al reiniciar.','La copia sigue en su ubicación','Archivo retenido','Identidad distinta; eliminación bloqueada','Nombre ocupado; se conservan ambos','Revisa la ruta original','Resultado sin confirmar','Eliminación confirmada','Cerrar solo registro','Solo cierra registros, nunca archivos; no confirma el resultado anterior.','Exploración incompleta o registros ilegibles','Volver a copias','Nunca reemplaza un archivo en el nombre original.'],
 fr:['Supprimer cette copie','Supprime la copie vérifiée et conserve la cible actuelle. Les descripteurs ouverts et liens physiques ne sont pas verrouillés.','Copie supprimée','Nettoyage inachevé','Aucun enregistrement en attente','Remettre à sa place','Remise en place ; vérifier avant de supprimer.','Résultat non confirmé ; aucune suppression automatique.','Les fichiers retenus ne sont pas supprimés au redémarrage.','Copie à sa place','Fichier retenu','Identité différente ; suppression bloquée','Nom occupé ; les deux fichiers sont conservés','Vérifier le chemin d’origine','Résultat non confirmé','Suppression confirmée','Fermer le suivi seulement','Ne supprime aucun fichier et ne confirme pas le résultat précédent.','Analyse incomplète ou données illisibles','Retour aux copies','Ne remplace jamais un fichier au nom d’origine.']
};
const backupLabels={
 zh:['备份管理','已登记备份','当前可用空间','独立备份文件逻辑大小','旧版未登记的文件可在目录里查看，不会自动认领或删除。','暂无登记的备份','导入中或等待恢复','文件仍在，内容可能已修改','文件已不存在','文件身份已变化','暂时无法读取','查看详情','创建时审阅','当前内容','审阅备份内容','选择副本恢复','源备份继续保留；目标同名时仍需审阅并确认替换。','扫描不完整或有无法读取的记录','备份位置','登记时间','返回备份列表','未知','可用空间只是当前观察；逻辑大小不等于可回收空间。','来源：工作区备份'],
 en:['Manage backups','Registered backups','Available storage now','Unique backup logical bytes','Older unregistered files remain in the directory; no automatic adoption or deletion.','No registered backups','Importing or awaiting recovery','File present; contents may have changed','File missing','File identity changed','Temporarily unavailable','View details','Original reviewed content','Current content','Review backup content','Select a copy to restore','The source backup is kept. Existing destinations still require review and confirmation.','Scan incomplete or records unreadable','Backup path','Recorded at','Back to backups','Unknown','Free storage is an observation; logical size is not reclaimable space.','Source: workspace backup'],
 ja:['バックアップ管理','登録済みバックアップ','現在の空き容量','重複を除く論理サイズ','旧版の未登録ファイルはディレクトリで確認できます。自動登録・削除はしません。','登録済みバックアップなし','インポート中・復旧待ち','ファイルあり。内容変更の可能性あり','ファイルがありません','ファイルの識別情報が変わりました','一時的に読み取れません','詳細','登録時の確認内容','現在の内容','内容を確認','コピーを復元用に選択','元のバックアップは保持します。同名の保存先は確認後に置換します。','走査が不完全、または読めない記録があります','バックアップ先','登録日時','一覧に戻る','不明','空き容量は現在の観測です。論理サイズは解放可能な容量ではありません。','ソース：ワークスペースのバックアップ'],
 ko:['백업 관리','등록된 백업','현재 사용 가능 공간','중복을 제외한 논리 크기','이전 버전의 미등록 파일은 디렉터리에 유지됩니다. 자동 등록하거나 삭제하지 않습니다.','등록된 백업 없음','가져오는 중 또는 복구 대기','파일 있음; 내용은 변경될 수 있음','파일 없음','파일 식별 정보 변경됨','일시적으로 읽을 수 없음','자세히 보기','등록 당시 검토 내용','현재 내용','백업 내용 검토','복원할 복사본 선택','원본 백업은 유지됩니다. 동일 이름 대상은 검토 후 교체합니다.','검색 미완료 또는 읽을 수 없는 기록','백업 경로','등록 시간','백업 목록으로','알 수 없음','여유 공간은 현재 관측값이며 논리 크기는 회수 가능한 공간과 다릅니다.','출처: 작업 공간 백업'],
 es:['Gestionar copias','Copias registradas','Espacio disponible ahora','Bytes lógicos únicos','Los archivos antiguos sin registrar siguen en el directorio; no se adoptan ni eliminan automáticamente.','Sin copias registradas','Importando o esperando recuperación','Archivo presente; el contenido puede cambiar','Archivo ausente','Identidad del archivo cambiada','No disponible temporalmente','Ver detalles','Contenido revisado originalmente','Contenido actual','Revisar contenido','Elegir copia para restaurar','Se conserva la copia de origen. Un destino existente requiere revisión y confirmación.','Análisis incompleto o registros ilegibles','Ruta de copia','Registrado el','Volver a copias','Desconocido','El espacio libre es una observación; el tamaño lógico no indica el espacio recuperable.','Origen: copia del espacio de trabajo'],
 fr:['Gérer les copies','Copies enregistrées','Espace disponible','Taille logique unique','Les anciens fichiers non enregistrés restent dans le dossier ; aucun ajout ni effacement automatique.','Aucune copie enregistrée','Import en cours ou récupération en attente','Fichier présent, contenu modifiable','Fichier absent','Identité du fichier modifiée','Temporairement illisible','Voir les détails','Contenu vérifié à l’origine','Contenu actuel','Vérifier le contenu','Choisir une copie à restaurer','La copie source est conservée. Une cible existante exige vérification et confirmation.','Analyse incomplète ou registres illisibles','Chemin de copie','Date d’enregistrement','Retour aux copies','Inconnu','L’espace libre est une observation ; la taille logique ne prédit pas l’espace libérable.','Source : copie de l’espace de travail']
};
const recoveryLabels={
 zh:['上次导入恢复','查看详情','原文件已保留在原位置','保留已发布状态，未自动恢复旧文件','发现同名文件，已保留双方','请检查保留的备份','记录的备份位置','关闭提示','关闭提示不会删除文件或备份','暂无恢复提示','最多显示最近100条'],
 en:['Previous import recovery','View details','Original file is available at its original path','Publication retained; no automatic rollback','A concurrent name was kept; both files survive','Review the retained backup','Recorded backup path','Dismiss notice','Dismissing keeps files and backups','No recovery notices','Shows up to 100 recent notices'],
 ja:['前回のインポート復旧','詳細','元のファイルは元の場所にあります','公開済み状態を保持し、自動で元に戻しません','同名ファイルと元のファイルを保持しました','保持したバックアップを確認してください','記録されたバックアップ先','通知を閉じる','ファイルやバックアップは削除しません','復旧通知はありません','最新100件まで表示'],
 ko:['이전 가져오기 복구','자세히 보기','원본 파일이 원래 경로에 있습니다','게시 상태를 유지하며 자동 복원하지 않습니다','동일 이름 파일과 원본을 모두 보관했습니다','보관된 백업을 확인하세요','기록된 백업 경로','알림 닫기','파일과 백업은 삭제하지 않습니다','복구 알림이 없습니다','최근 100개까지 표시'],
 es:['Recuperación de la importación anterior','Ver detalles','El original está en su ruta original','Se conserva la publicación; sin reversión automática','Se conservan el archivo concurrente y el original','Revisa la copia conservada','Ruta de copia registrada','Cerrar aviso','Se conservan los archivos y las copias','No hay avisos de recuperación','Hasta 100 avisos recientes'],
 fr:['Récupération de l’import précédent','Voir les détails','L’original est disponible à son emplacement initial','Publication conservée, sans restauration automatique','Le fichier concurrent et l’original sont conservés','Vérifiez la copie conservée','Emplacement de copie enregistré','Fermer l’avis','Les fichiers et copies sont conservés','Aucun avis de récupération','100 avis récents au maximum']
};
const transferLabels={
 zh:['正在写入工作区','取消导入','正在取消，请等待清理','已取消，所选文件仍可重试','准备','写入','同步','校验','发布','已完成'],
 en:['Importing into workspace','Cancel import','Cancelling; waiting for cleanup','Cancelled. The selected file is available for retry.','Preparing','Writing','Syncing','Verifying','Publishing','Completed'],
 ja:['ワークスペースに書き込み中','インポートを中止','中止処理と後片付け中','中止しました。選択したファイルで再試行できます。','準備','書き込み','同期','検証','公開','完了'],
 ko:['작업 공간에 쓰는 중','가져오기 취소','취소 및 정리 중','취소했습니다. 선택한 파일로 다시 시도할 수 있습니다.','준비','쓰기','동기화','검증','게시','완료'],
 es:['Importando al espacio','Cancelar importación','Cancelando y limpiando','Cancelado. El archivo sigue disponible para reintentar.','Preparando','Escribiendo','Sincronizando','Verificando','Publicando','Completado'],
 fr:['Import dans l’espace','Annuler l’import','Annulation et nettoyage','Annulé. Le fichier sélectionné reste disponible.','Préparation','Écriture','Synchronisation','Vérification','Publication','Terminé']
};
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
 let importOperation:ImportOperation|null=null;
 let recoveryWorkspace:string|null=null,recoveryOffset=0,recoveryTotal=0,recoveryReturnMode='home';
 let recoveries:ImportRecovery[]=[];
 let backupOffset=0,backupReturnMode='home';
 let cleanupOffset=0;let cleanupPage:CleanupPage={cleanups:[],offset:0,total:0,incomplete:false,unreadableEntries:0};
 let backupPage:BackupPage={backups:[],offset:0,total:0,logicalBytes:0,availableBytes:null,incomplete:false,unreadableRecords:0};
 let backup:Backup|null=null,backupReview:BackupReview|null=null;
 let snapshot:Snapshot={workspaceId:null,workspaces:[],shells:[],draftInstallAllowed:false};
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
  const tr=transferLabels[locale.split(/[-_]/)[0] as keyof typeof transferLabels]??transferLabels.en;
  const ir=replacementLabels[locale.split(/[-_]/)[0] as keyof typeof replacementLabels]??replacementLabels.en;
  const cl=cleanupLabels[locale.split(/[-_]/)[0] as keyof typeof cleanupLabels]??cleanupLabels.en;
  const bl=backupLabels[locale.split(/[-_]/)[0] as keyof typeof backupLabels]??backupLabels.en;
  const rl=recoveryLabels[locale.split(/[-_]/)[0] as keyof typeof recoveryLabels]??recoveryLabels.en;
  const ip=previewLabels[locale.split(/[-_]/)[0] as keyof typeof previewLabels]??previewLabels.en;
  const pic=pictureLabels[locale.split(/[-_]/)[0] as keyof typeof pictureLabels]??pictureLabels.en;
  const pdf=pdfLabels[locale.split(/[-_]/)[0] as keyof typeof pdfLabels]??pdfLabels.en;
  const cf=chatFileLabels[locale.split(/[-_]/)[0] as keyof typeof chatFileLabels]??chatFileLabels.en;
  const api=ctx.workspacePage;if(!api)throw Error('Workspace page needs API12');
  if(mode!=='importTransfer')snapshot=await api.query('snapshot') as unknown as Snapshot;
  if(treeWorkspace!==snapshot.workspaceId){treeWorkspace=snapshot.workspaceId;treeResult=null;treeEntries.clear()}
  const refresh=async()=>{snapshot=await api.query('snapshot') as unknown as Snapshot};
  const loadRecoveries=async():Promise<void>=>{
   if(!importsEnabled||!snapshot.workspaceId){recoveries=[];recoveryTotal=0;return}
   const result=await api.query('importRecoveries',{offset:recoveryOffset}) as unknown as {recoveries:ImportRecovery[];total:number};
   recoveries=result.recoveries;recoveryTotal=result.total;
   if(recoveryOffset>=recoveryTotal&&recoveryOffset>0){recoveryOffset=Math.max(0,Math.floor((recoveryTotal-1)/5)*5);await loadRecoveries()}
  };
  if(mode!=='importTransfer'&&(recoveryWorkspace!==snapshot.workspaceId||!event||['home','directory','refresh'].includes(event.action))){
   if(recoveryWorkspace!==snapshot.workspaceId)recoveryOffset=0;
   recoveryWorkspace=snapshot.workspaceId;await loadRecoveries();
  }
  const loadCleanups=async()=>{
   cleanupPage=await api.query('importBackupCleanups',{offset:cleanupOffset}) as unknown as CleanupPage;
   if(cleanupOffset>=cleanupPage.total&&cleanupOffset>0){cleanupOffset=Math.max(0,Math.floor((cleanupPage.total-1)/5)*5);cleanupPage=await api.query('importBackupCleanups',{offset:cleanupOffset}) as unknown as CleanupPage}
  };
  const loadBackups=async()=>{
   backupPage=await api.query('importBackups',{offset:backupOffset}) as unknown as BackupPage;
   if(backupOffset>=backupPage.total&&backupOffset>0){backupOffset=Math.max(0,Math.floor((backupPage.total-1)/5)*5);backupPage=await api.query('importBackups',{offset:backupOffset}) as unknown as BackupPage}
  };
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
  const pollImport=async()=>{
   if(!importOperation)return;
   importOperation=await api.query('importOperation',{operationId:importOperation.operationId}) as unknown as ImportOperation;
   if(importOperation.status==='SUCCEEDED'){
    if(!importOperation.result)throw Error('Missing native import result');
    snapshot=importOperation.result;message=t[28];
    if(snapshot.lastImport?.backupPath)message+=` · ${ir[3]}: ${snapshot.lastImport.backupPath}`;
    importSelection=null;importPreview=null;importPicture=null;importPdf=null;importReview=null;
    treeRevision++;treeResult=null;treeEntries.clear();mode='list';await list();importOperation=null;
   }else if(importOperation.status==='FAILED'||importOperation.status==='CANCELLED'){
    message=importOperation.status==='CANCELLED'?tr[3]:t[22]+(importOperation.error??'Import failed');
    importReview=null;mode='import';importOperation=null;
   }
  };
  if(event){
   // Passive tree fetches follow writes automatically; keep their result/backup visible.
   if(event.action!=='treeLoad')message='';try{
    const action=event.action;
    if(action==='cleanupOpen'){cleanupOffset=0;await loadCleanups();mode='cleanups'}
    else if(action==='cleanupPrev'||action==='cleanupNext'||action==='cleanupRefresh'){
     cleanupOffset=action==='cleanupPrev'?Math.max(0,cleanupOffset-5):action==='cleanupNext'?cleanupOffset+5:0;await loadCleanups();
    }
    else if(action==='cleanupBack'){await loadBackups();mode='backups'}
    else if(action==='cleanupRestore'||action==='cleanupDismiss'){
     await api.perform();message=action==='cleanupRestore'?cl[6]:cl[17];await loadCleanups();
    }
    else if(action==='backupClean'&&backup&&backupReview){
     backupReview=null;await api.perform();message=cl[2];await loadBackups();mode='backups';
    }
    else if(action==='backupOpen'){backupReturnMode=mode;backupOffset=0;backup=null;backupReview=null;await loadBackups();mode='backups'}
    else if(action==='backupPrev'||action==='backupNext'||action==='backupRefresh'){
     backupOffset=action==='backupPrev'?Math.max(0,backupOffset-5):action==='backupNext'?backupOffset+5:0;await loadBackups();
    }
    else if(action==='backupBack'){backup=null;backupReview=null;mode=backupReturnMode}
    else if(action==='backupView'){
     const item=backupPage.backups[Number(event.nodeId.split('_').pop())];if(!item)throw Error('Backup list changed');
     backup=item;backupReview=null;mode='backupDetail';
    }
    else if(action==='backupList'){backupReview=null;await loadBackups();mode='backups'}
    else if(action==='backupReview'&&backup){
     backupReview=null;backupReview=await api.query('reviewBackup',{backupId:backup.id}) as unknown as BackupReview;
     if(backupReview.backupId!==backup.id||backupReview.path!==backup.path||backupReview.backupPath!==backup.backupPath)throw Error('Backup review changed');
    }
    else if(action==='backupSelect'&&backup&&backupReview){
     await api.perform();const chosen=await api.query('importSelection') as unknown as ImportSelection|null;
     if(chosen?.source?.kind!=='workspaceBackup'||chosen.source.backupId!==backup.id||chosen.sha256!==backupReview.sha256)throw Error('Backup selection changed');
     importSelection=chosen;importPath=backup.path;importPreview=null;importPicture=null;importPdf=null;importReview=null;backupReview=null;mode='import';
    }
    else if(action==='recoveryOpen'){recoveryReturnMode=mode;recoveryOffset=0;await loadRecoveries();mode='recoveries'}
    else if(action==='recoveryPrev'||action==='recoveryNext'||action==='recoveryRefresh'){
     recoveryOffset=action==='recoveryPrev'?Math.max(0,recoveryOffset-5):action==='recoveryNext'?recoveryOffset+5:0;await loadRecoveries();
    }
    else if(action==='recoveryBack'){mode=recoveryReturnMode}
    else if(action==='dismissRecovery'){await api.perform();await loadRecoveries()}
    else if(action==='importPoll'){await pollImport()}
    else if(action==='cancelImport'&&importOperation){await api.perform();await pollImport()}
    else if(action==='chatFiles'){chatReturnMode=mode;chatFileOffset=0;await loadChatFiles();mode='chatFiles'}
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
     prepare('startImport',{id:importSelection.id,path:importPath,sha256:importSelection.sha256,reviewId:importReview.reviewId});
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
     if(pending.operation==='startImport'){
      const started=await api.perform() as unknown as {operationId:string};
      importOperation={operationId:started.operationId,status:'RUNNING'};pending=null;mode='importTransfer';await pollImport();
     }else{
     snapshot=await api.perform() as unknown as Snapshot;const operation=pending.operation;pending=null;message=t[28];
     if(operation==='importReviewed'){importSelection=null;importPreview=null;importPicture=null;importPdf=null;importReview=null;if(snapshot.lastImport?.backupPath)message+=` · ${ir[3]}: ${snapshot.lastImport.backupPath}`}
     if(['write','writeDraft','makeDirectory','moveEntry','deleteEntry','importReviewed'].includes(operation)){treeRevision++;treeResult=null;treeEntries.clear()}
     mode=['write','writeDraft','makeDirectory','moveEntry','deleteEntry','importReviewed'].includes(operation)?'list':operation==='installDraft'?'drafts':'home';
     if(mode==='list')await list();if(mode==='drafts')await loadDrafts();
     }
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
    if(mode!=='importTransfer')await refresh();
   }catch(error){
    if(pending?.operation==='startImport'){pending=null;importReview=null;mode='import'}
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
  if(importsEnabled&&recoveryTotal>0&&['home','list'].includes(mode))nodes.push({id:'recovery_summary',kind:'card',children:[
   text('recovery_summary_title',`${rl[0]} (${recoveryTotal})`),button('recoveryOpen',rl[1])]});
  nodes.push(text('tree_meta',JSON.stringify({workspaceId:snapshot.workspaceId,revision:treeRevision,result:treeResult}),'caption'),
   {id:'treePath',kind:'field',label:'Directory',value:''},
   {id:'treeOffset',kind:'field',label:'Offset',value:'0'},
   {id:'treeTarget',kind:'field',label:'File',value:''},
   button('tree_load','Load directory','treeLoad'),button('tree_open','Open file','treeOpen'));
  if(mode==='importTransfer'&&importOperation){
   const progress=importOperation.progress??{stage:'PREPARING',completed:0,total:importSelection?.bytes??0};
   const stage=['PREPARING','WRITING','SYNCING','VERIFYING','PUBLISHING','COMMITTED'].indexOf(progress.stage);
   nodes.push(text('transfer_title',tr[0],'title'),text('transfer_stage',tr[Math.max(0,stage)+4]),
    text('transfer_bytes',`${progress.completed} / ${progress.total} B`),
    text('import_progress',JSON.stringify(progress),'caption'),button('import_poll',tr[0],'importPoll'));
   if(importOperation.status==='CANCELLING')nodes.push(text('transfer_cancel_wait',tr[2]));
   else footer.push({...button('cancelImport',tr[1]),hostAction:{kind:'workspace.perform',operation:'cancelImport',arguments:{operationId:importOperation.operationId}}});
  }else if(mode==='home'){
   nodes.push(text('name',current?.name??t[20],'title'));
   nodes.push(button('create',t[1]),button('choose',t[2]));
   if(current){nodes.push(button('directory',t[7]),...(importsEnabled?[selectImport(),fromChat(),button('backupOpen',bl[0])]:[]),button('shell',t[31]),text('shells',snapshot.shells.map(s=>s.name).join(' · ')),button('drafts',t[23]),button('prepareUnbind',t[6]));if(importSelection)nodes.push(button('showImport',im[2]))}
  }else if(mode==='create'||mode==='shell'){
   nodes.push({id:'name',kind:'field',label:mode==='create'?t[3]:t[30],value:mode==='create'?'':`Shell ${snapshot.shells.length+1}`});
   footer.push(button('home',t[8]),button(mode==='create'?'prepareCreate':'prepareShell',t[4]));
  }else if(mode==='choose'){
   names=snapshot.workspaces.slice(workspaceOffset,workspaceOffset+40);names.forEach((w,i)=>nodes.push(button(`bind_${i}`,w.name,'prepareBind')));
   footer.push(button('home',t[8]));if(workspaceOffset>0)footer.push(button('choosePrev',t[17]));if(workspaceOffset+40<snapshot.workspaces.length)footer.push(button('chooseNext',t[18]));
  }else if(mode==='backups'){
   nodes.push(text('backup_title',bl[0],'title'),text('backup_count',`${bl[1]}: ${backupPage.total}`),
    text('backup_available',`${bl[2]}: ${backupPage.availableBytes===null?bl[21]:backupPage.availableBytes+' B'}`),
    text('backup_logical',`${bl[3]}: ${backupPage.logicalBytes} B`),text('backup_space_note',bl[22],'caption'),text('backup_legacy_note',bl[4],'caption'));
   if(backupPage.incomplete||backupPage.unreadableRecords)nodes.push(text('backup_scan_warning',`${bl[17]} (${backupPage.unreadableRecords})`));
   if(!backupPage.backups.length)nodes.push(text('backup_empty',bl[5]));
   backupPage.backups.forEach((r,i)=>nodes.push({id:`backup_${i}`,kind:'card',children:[
    text(`backup_path_${i}`,r.path),text(`backup_status_${i}`,r.pending?bl[6]:r.status==='PRESENT'?bl[7]:r.status==='MISSING'?bl[8]:r.status==='CHANGED_IDENTITY'?bl[9]:bl[10],'caption'),
    ...(r.currentBytes===null?[]:[text(`backup_bytes_${i}`,`${r.currentBytes} B`,'caption')]),button(`backup_view_${i}`,bl[11],'backupView')]}));
   footer.push(button('backupBack',t[8]),button('backupRefresh',t[9]),button('cleanupOpen',cl[3]));if(backupOffset>0)footer.push(button('backupPrev',t[17]));if(backupOffset+5<backupPage.total)footer.push(button('backupNext',t[18]));
  }else if(mode==='cleanups'){
   nodes.push(text('cleanup_title',cl[3],'title'),text('cleanup_note',cl[8],'caption'));
   if(cleanupPage.incomplete||cleanupPage.unreadableEntries)nodes.push(text('cleanup_warning',`${cl[18]} (${cleanupPage.unreadableEntries})`));
   if(!cleanupPage.cleanups.length)nodes.push(text('cleanup_empty',cl[4]));
   cleanupPage.cleanups.forEach((r,i)=>{
    const state=r.status==='SOURCE_AVAILABLE'?cl[9]:r.status==='HELD'?cl[10]:r.status==='HELD_CHANGED'?cl[11]:r.status==='SOURCE_AND_HELD'?cl[12]:r.status==='UNAVAILABLE'?cl[13]:r.status==='DELETED'?cl[15]:cl[14];
    nodes.push({id:`cleanup_${i}`,kind:'card',children:[text(`cleanup_path_${i}`,r.path),text(`cleanup_backup_${i}`,r.backupPath,'caption'),text(`cleanup_status_${i}`,state),
     ...(r.heldBytes===null?[]:[text(`cleanup_bytes_${i}`,`${r.heldBytes} B`,'caption')]),
     ...(['SOURCE_AVAILABLE','HELD','HELD_CHANGED','SOURCE_AND_HELD'].includes(r.status)?[{...button(`cleanup_restore_${i}`,cl[5],'cleanupRestore'),confirm:cl[20],hostAction:{kind:'workspace.perform' as const,operation:'restoreImportBackupCleanup' as const,arguments:{backupId:r.id}}}]:[]),
     ...(['UNCONFIRMED','DELETED','DISMISSED'].includes(r.status)?[text(`cleanup_unconfirmed_${i}`,cl[7],'caption'),{...button(`cleanup_dismiss_${i}`,cl[16],'cleanupDismiss'),confirm:cl[17],hostAction:{kind:'workspace.perform' as const,operation:'dismissImportBackupCleanup' as const,arguments:{backupId:r.id}}}]:[])]});
   });
   footer.push(button('cleanupBack',cl[19]),button('cleanupRefresh',t[9]));if(cleanupOffset>0)footer.push(button('cleanupPrev',t[17]));if(cleanupOffset+5<cleanupPage.total)footer.push(button('cleanupNext',t[18]));
  }else if(mode==='backupDetail'&&backup){
   const date=new Date(backup.createdAt);const stamp=Number.isFinite(date.getTime())?date.toISOString():bl[21];
   nodes.push(text('backup_detail_path',backup.path,'title'),text('backup_detail_location',`${bl[18]}: ${backup.backupPath}`),text('backup_created',`${bl[19]}: ${stamp}`,'caption'),
    text('backup_original_bytes',`${bl[12]}: ${backup.originalBytes} B`),text('backup_original_sha',backup.originalSha256,'caption'),text('backup_restore_note',bl[16]));
   if(backupReview)nodes.push(text('backup_current_bytes',`${bl[13]}: ${backupReview.bytes} B`),text('backup_current_sha',backupReview.sha256,'caption'));
   footer.push(button('backupList',bl[20]),{...button('backupReview',bl[14]),enabled:backup.status==='PRESENT'&&!backup.pending},button('cleanupOpen',cl[3]));
   if(backupReview)footer.push({...button('backupSelect',bl[15]),confirm:bl[16],hostAction:{kind:'workspace.perform',operation:'selectImportBackup',arguments:{backupId:backupReview.backupId,sha256:backupReview.sha256,path:backupReview.path}}});
   if(backupReview)footer.push({...button('backupClean',cl[0]),confirm:cl[1],hostAction:{kind:'workspace.perform',operation:'cleanupImportBackup',arguments:{backupId:backupReview.backupId,sha256:backupReview.sha256,path:backupReview.path}}});
  }else if(mode==='recoveries'){
   nodes.push(text('recovery_title',rl[0],'title'),text('recovery_limit',rl[10],'caption'));
   if(!recoveries.length)nodes.push(text('recovery_empty',rl[9]));
   recoveries.forEach((entry,i)=>nodes.push({id:`recovery_${i}`,kind:'card',children:[
    text(`recovery_path_${i}`,entry.path),
    text(`recovery_status_${i}`,entry.status==='ORIGINAL_AVAILABLE'?rl[2]:entry.status==='PUBLISHED'?rl[3]:entry.status==='CONFLICT'?rl[4]:rl[5]),
    ...(entry.backupPath?[text(`recovery_backup_${i}`,`${rl[6]}: ${entry.backupPath}`,'caption')]:[]),
    {...button(`recovery_dismiss_${i}`,rl[7],'dismissRecovery'),confirm:rl[8],hostAction:{kind:'workspace.perform',operation:'dismissImportRecovery',arguments:{recoveryId:entry.id}}}
   ]}));
   footer.push(button('recoveryBack',t[8]),button('recoveryRefresh',t[9]));
   if(recoveryOffset>0)footer.push(button('recoveryPrev',t[17]));if(recoveryOffset+5<recoveryTotal)footer.push(button('recoveryNext',t[18]));
  }else if(mode==='list'){
   nodes.push(text('directory',directory||'/','title'),{id:'directory_actions',kind:'row',children:[
    ...(importsEnabled?[selectImport(),fromChat(),button('backupOpen',bl[0])]:[]),button('new',t[10]),button('mkdir',f[0])]});
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
    text('import_source',importSelection.source?.kind==='workspaceBackup'?`${bl[23]} · ${importSelection.source.path}`:importSelection.source?.kind==='chatAttachment'?`${cf[4]} · ${importSelection.source.messageId}`:im[6],'caption'),text('import_sha',importSelection.sha256,'caption'),text('import_limit',im[4]),
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
   else if(pending.operation==='startImport'&&importSelection){
    nodes.push(text('import_name',importSelection.name),text('import_type',`${importSelection.mimeType} · ${importSelection.bytes} B`),text('target',String(pending.arguments.path)),text('import_sha',importSelection.sha256,'caption'));
    if(importReview?.target)nodes.push(text('replace_title',ir[0],'title'),text('replace_size',`${importReview.target.bytes} B`),text('replace_sha',importReview.target.sha256,'caption'),text('replace_warning',ir[2]));
    else nodes.push(text('replace_absent',ir[4]));
   }
   else nodes.push(text('operation',JSON.stringify(pending.arguments,null,2)));
   if(pending.operation==='installDraft')nodes.push(text('warning',t[26]));
   if(pending.operation==='deleteEntry')nodes.push(text('warning',f[4]));
   footer.push(button('cancel',t[15]),{...button('confirm',pending.operation==='startImport'&&importReview?.target?ir[1]:t[14]),hostAction:pending});
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

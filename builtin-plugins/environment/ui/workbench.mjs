const labels={
 zh:['软件管理','安装 Python / pip、Node.js / npm 或 Java 17','环境说明','Python · Node.js/npm · 共享工作区'],
 en:['Software','Install Python / pip, Node.js / npm or Java 17','About this environment','Python · Node.js/npm · shared workspace'],
 ja:['ソフトウェア','Python / pip、Node.js / npm、Java 17をインストール','環境について','Python · Node.js/npm · 共有ワークスペース'],
 ko:['소프트웨어','Python / pip, Node.js / npm 또는 Java 17 설치','환경 정보','Python · Node.js/npm · 공유 작업 공간'],
 es:['Software','Instalar Python / pip, Node.js / npm o Java 17','Acerca del entorno','Python · Node.js/npm · espacio compartido'],
 fr:['Logiciels','Installer Python / pip, Node.js / npm ou Java 17','À propos de cet environnement','Python · Node.js/npm · espace partagé']
};
const element=(tag,classes,text)=>{const node=document.createElement(tag);if(classes)node.className=classes;if(text!==undefined)node.textContent=text;return node};
export function polishEnvironment(root,footer,state,env,open,revealFailure){
 const t=labels[state.locale.split(/[-_]/)[0]]??labels.en;
 const nodes=Object.fromEntries([...root.querySelectorAll('[data-node]'),...footer.querySelectorAll('[data-node]')].map(node=>[node.dataset.node,node]));
 const page=element('div','environment-page');
 const status=element('section','environment-status');status.dataset.stage=env.stage;
 const heading=element('div','environment-heading'),title=element('div','environment-title');
 title.append(element('h2',null,'Ubuntu 24.04'));
 if(nodes.status){nodes.status.setAttribute('role','status');title.append(nodes.status)}
 heading.append(title);
 if(nodes.refresh){const refresh=nodes.refresh;refresh.title=refresh.textContent;refresh.setAttribute('aria-label',refresh.textContent);refresh.textContent='↻';heading.append(refresh)}
 status.append(heading,element('p','environment-summary',t[3]));
 if(env.busy){
  const progress=element('progress','environment-progress');progress.max=100;progress.setAttribute('aria-label',nodes.status?.textContent??'Ubuntu');
  if(env.stage==='DOWNLOADING'&&Number.isFinite(env.total)&&env.total>0&&Number.isFinite(env.bytes)&&env.bytes>=0)progress.value=Math.max(0,Math.min(100,env.bytes/env.total*100));
  status.append(progress);if(nodes.progress)status.append(nodes.progress);
 }
 if(nodes.failure){nodes.failure.setAttribute('role','alert');status.append(nodes.failure)}
 if(nodes.unsupported)status.append(nodes.unsupported);
 page.append(status);
 const primary=element('section','environment-primary');
 for(const id of ['install','stop','newShell','workspaceNeeded'])if(nodes[id])primary.append(nodes[id]);
 if(primary.childElementCount)page.append(primary);
 function disclosure(id,label,children){
  const details=element('details','environment-details');details.id=id;details.open=open.has(id);
  const summary=element('summary',null,label),body=element('div','environment-details-body');
  body.append(...children);details.append(summary,body);return details;
 }
 const software=['software_PYTHON','software_NODE','software_JAVA'].map(id=>nodes[id]).filter(Boolean);
 if(software.length){
  const details=disclosure('environment-software',t[0],[element('p','caption',t[1]),...software]);page.append(details);
 }
 if(nodes.log){
  const details=disclosure('environment-log',nodes.logTitle?.textContent??'Log',[nodes.log]);
  if(revealFailure)details.open=true;
  page.append(details);
 }
 const info=[nodes.intro,nodes.retryHint].filter(Boolean);
 if(info.length)page.append(disclosure('environment-info',t[2],info));
 if(nodes.environment_meta)page.append(nodes.environment_meta);
 root.replaceChildren(page);footer.hidden=true;
}

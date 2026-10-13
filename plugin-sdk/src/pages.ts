/** Page state and validated actions. API10 HTML owns layout; API9 uses native widgets. */
export type PageHostAction =
  | { kind:'workspace.perform'; operation:'create'|'createShell'|'bind'|'unbind'|'write'|'writeDraft'|'installDraft'; arguments:Record<string,import('./index.js').JsonValue> }
  | { kind:'document.open'; mimeTypes:string[]; maxBytes:number }
  | { kind:'document.save'; name:string; mimeType:string; maxBytes:number }
  | { kind:'postTurn.retry'; jobId:string; attempt:number }
  | { kind:'postTurn.delete'; jobId:string; attempt:number; status:'WAITING'|'SUCCEEDED'|'FAILED'|'SKIPPED' }
  | { kind:'postTurn.stop'; jobId:string; attempt:number }
  | { kind:'postTurn.cleanup' };
export type PageNode =
  | { id: string; kind: 'text'; text: string; tone?: 'body' | 'title' | 'caption' }
  | { id: string; kind: 'column' | 'row' | 'card'; children: PageNode[] }
  | { id: string; kind: 'button'; text: string; action: string; confirm?: string; enabled?: boolean; hostAction?:PageHostAction }
  | { id: string; kind: 'field'; label: string; value: string; action?: string; multiline?: boolean }
  | { id: string; kind: 'choice'; label: string; value: string; action: string; options: { value: string; label: string }[] }
  | { id: string; kind: 'graph'; nodes: { id: string; label: string }[];
      edges: { from: string; to: string; label: string }[]; action?: string };
export interface PluginPageView {
  title: string;
  nodes: PageNode[];
  /** Fixed below the scroll area, above the host's keyboard/navigation insets. */
  footer?: Extract<PageNode, { kind: 'button' }>[];
}
/** Actions are resolved from the current displayed node by the host. */
export interface PluginPageEvent {
  nodeId: string;
  action: string;
  value?: string;
  /** Current field values, validated against the displayed page by the host. */
  fields: Record<string, string>;
}
export interface PluginPageInput { locale: string; event: PluginPageEvent | null }

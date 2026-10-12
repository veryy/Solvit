/** Native Android views, without DOM/React or direct access to host navigation. */
export type PageNode =
  | { id: string; kind: 'text'; text: string; tone?: 'body' | 'title' | 'caption' }
  | { id: string; kind: 'column' | 'row' | 'card'; children: PageNode[] }
  | { id: string; kind: 'button'; text: string; action: string; confirm?: string; enabled?: boolean }
  | { id: string; kind: 'field'; label: string; value: string; action?: string; multiline?: boolean }
  | { id: string; kind: 'choice'; label: string; value: string; action: string; options: { value: string; label: string }[] }
  | { id: string; kind: 'graph'; nodes: { id: string; label: string }[];
      edges: { from: string; to: string; label: string }[]; action?: string };
export interface PluginPageView { title: string; nodes: PageNode[] }
/** Actions are resolved from the current displayed node by the host. */
export interface PluginPageEvent {
  nodeId: string;
  action: string;
  value?: string;
  /** Current field values, validated against the displayed page by the host. */
  fields: Record<string, string>;
}
export interface PluginPageInput { locale: string; event: PluginPageEvent | null }

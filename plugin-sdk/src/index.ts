/** JSON-only bridge. No Node, DOM, timers, filesystem or credentials are exposed. */
export type { PageNode, PluginPageView, PluginPageEvent, PluginPageInput, PageHostAction } from './pages.js';
import type { PluginPageView, PluginPageInput } from './pages.js';
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
export interface PluginTask {
  id:string; status:'WAITING'|'RUNNING'|'SUCCEEDED'|'FAILED'|'SKIPPED'; attempt:number; updatedAt:number;
  failure:string|null; roleName:string|null; model:string; sessionId:string; userMessageId:string; assistantMessageId:string;
}
export interface PluginTaskListing {
  available:boolean; jobs:PluginTask[]; activeJobId:string|null; nextCursor:{updatedAt:number;id:string}|null;
}
/** API16 fixed root candidates; no absolute path or chat/workspace selector is exposed. */
export interface WorkspaceInstructionSource {
  readonly path:'AGENTS.md'|'CLAUDE.md'|'AGENTS.local.md'|'CLAUDE.local.md';
  readonly status:'TEXT'|'ABSENT'|'BINARY'|'OVERSIZED'|'UNAVAILABLE';
  readonly text?:string|null; readonly sha256?:string|null;
}
export interface WorkspaceInstructions {readonly available:boolean;readonly sources:readonly WorkspaceInstructionSource[]}
export interface PluginContext {
  /** API12, human page only. The host captures the chat; scripts cannot supply a chat/workspace id to file queries. */
  readonly workspacePage?: {query(operation:'snapshot'|'list'|'read'|'drafts'|'draft'|'changes'|'changeContent',args?:JsonObject):Promise<JsonValue>;
    perform():Promise<JsonValue>};
  /** API11, page-only. One confirmed displayed control; system picker owns URI access. */
  readonly documents?: {
    open(): Promise<{name:string;text?:string;files?:Record<string,string>} | null>;
    save(text:string): Promise<boolean>;
  };
  /** API 9, page-only. perform consumes the one native control confirmed on the displayed button. */
  readonly jobs?: { list(options?:{cursor?:{updatedAt:number;id:string}|null}):Promise<PluginTaskListing>;
    perform():Promise<{changed:number;receipts:number}> };
  /** API 8: available only during an opted-in afterTurn invocation; at most one call. */
  readonly model?: { complete(options: { instructions: string; input: string }): Promise<string> };
  /** API 6: anonymous Android browser observation; requires network permission. */
  readonly web?: { visit(url: string): Promise<{ url: string; title: string; text: string; truncated: boolean; searchResults?: boolean;
    links: { url: string; title: string; snippet: string }[] }> };
  /** API 5: persistent Android PTY, scoped to the approved chat/plugin/role. */
  readonly terminal?: { call(operation: 'open' | 'status' | 'input' | 'interrupt' | 'close', args: JsonObject): Promise<JsonValue> };
  /** Optional on hosts older than Solvit 0.7.0; use JS Date as a UTC fallback. */
  /** API 4; operations are bound to the active chat and the exact approved tool arguments. */
  readonly workspace?: { call(operation: 'info' | 'read' | 'write' | 'createShell' | 'start' | 'status' | 'cancel', args?: JsonObject): Promise<JsonValue>;
    /** API16, beforeModel only + workspace grant; host captures the current conversation. */
    instructions?():Promise<WorkspaceInstructions> };
  readonly plugins?: { draft(args: { manifestJson: string; source: string }): Promise<JsonValue> };
  readonly clock?: { now(): Promise<{ utc: string; localTime: string; timezone: string; timezoneOffsetMinutes: number;
    utcOffset?: string; weekday?: number; displayLocal?: string; displayUtc?: string }> };
  readonly role?: { readonly id: string; readonly name: string; readonly skillIds: readonly string[] } | null;
  readonly settings: { get(key: string): Promise<string | null>;
    /** API10, live pages only. Atomic encrypted update of a declared setting. */
    compareAndSet?(key: string, expected: string | null, value: string): Promise<boolean> };
  readonly storage: {
    get<T extends JsonValue = JsonValue>(key: string): Promise<T | null>;
    set(key: string, value: JsonValue): Promise<null>;
    /** API 8: atomically replace a value only if it still equals the last read. */
    compareAndSet?(key: string, expected: JsonValue, value: JsonValue): Promise<boolean>;
  };
  readonly http: {
    request(options: { url: string; method?: 'GET' | 'POST' | 'DELETE'; headers?: Record<string, string>; body?: string; cancelBody?: string; timeoutMs?: number }): Promise<{ body: string; status: number; headers: Record<string, string> }>;
  };
}
export type ToolHandler = (arguments_: JsonObject, context: PluginContext) => JsonValue | Promise<JsonValue>;
/** API14: isolated per-turn strategy instance; all model/tool effects remain host validated. */
export interface AgentText { readonly text: string; readonly truncated: boolean }
export interface AgentObservation {
  readonly step: number; readonly nextModelRound: number;
  readonly phase: 'READY' | 'MODEL_READY' | 'TOOLS_RUNNING' | 'TOOLS_SETTLED' | 'COMPLETED';
  readonly userText: AgentText; readonly roleName: AgentText | null;
  readonly assistantText: AgentText; readonly reasoning: AgentText;
  readonly toolsTruncated: boolean;
  readonly tools: readonly {readonly index:number; readonly id:AgentText; readonly name:string;
    readonly arguments:AgentText; readonly stage:'CONFIRMING'|'RUNNING'|'SUCCEEDED'|'FAILED'|'DENIED'|null;
    readonly result:AgentText|null}[];
}
export interface AgentContext {
  /** API15 + agent.read grant: detached bounded snapshot, no model connection or protocol signatures. */
  observe?(): Promise<AgentObservation>;
  /** API15 + agent.read: settle a proposed call as DENIED without dispatching it. */
  decline?(index: number): Promise<number>;
  model(round: number): Promise<number>;
  tool(index: number): Promise<number>;
  settle(round: number): Promise<number>;
  finish(round: number): Promise<number>;
}
export interface PluginDefinition {
  agent?(input: Readonly<{maxRounds: number}>, context: AgentContext): Promise<void>;

  tools: Record<string, ToolHandler>;
  /** API 9: independent page instance; does not share activate() or chat state. */
  pages?: Record<string, (input: PluginPageInput, context: PluginContext) => PluginPageView | Promise<PluginPageView>>;
  activate?(context: PluginContext): void | Promise<void>;
  deactivate?(context: PluginContext): void | Promise<void>;
  beforeModel?(input: { userText: string; role: PluginContext['role']; settings?: Readonly<Record<string, string | null>> }, context: PluginContext): string | Promise<string>;
  /** Runs in a separate fresh instance; does not share activate() state with chat. */
  afterTurn?(input: { source: { turnId: string; sessionId: string; userMessageId: string; assistantMessageId: string;
    roleId: string | null; roleName: string | null }; userText: string; assistantText: string;
    settings: Readonly<Record<string, string | null>> }, context: PluginContext): void | Promise<void>;
}
export interface PluginManifest {
  id: string;
  name: string;
  description: string;
  version: string;
  apiVersion: 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17;
  /** API12: optional native dependencies, separate from permissions and API compatibility. */
  nativeServices?: ('terminal.pty'|'web.pages')[];
  pages?: { id: string; title: string; entry?: string }[];
  hooks?: ('beforeModel' | 'afterTurn')[];
  afterTurnEnabledSetting?: string;
  /** API14 + agent permission: a declared boolean setting, default false, globally selects this strategy. */
  agentEnabledSetting?: string;
  settings?: { key: string; label: string; kind: 'text' | 'url' | 'secret' | 'boolean'; required?: boolean; defaultValue?: 'true' | 'false'; pageManaged?: boolean }[];
  permissions: ('storage' | 'network' | 'workspace' | 'shell' | 'plugins' | 'model' | 'agent' | 'agent.read')[];
  dependencies: string[];
  tools: { name: string; description: string; inputSchema: JsonObject; exposure?: 'chat' | 'management' }[];
}
export function definePlugin<T extends PluginDefinition>(definition: T): T { return definition; }
export function textArgument(args: JsonObject, key: string, maxLength = 4000): string {
  const value = args[key];
  if (typeof value !== 'string' || !value.trim() || value.length > maxLength) throw new Error(`Invalid ${key}`);
  return value.trim();
}

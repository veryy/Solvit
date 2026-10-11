/** JSON-only bridge. No Node, DOM, timers, filesystem or credentials are exposed. */
export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = { [key: string]: JsonValue };
export interface PluginContext {
  readonly storage: {
    get<T extends JsonValue = JsonValue>(key: string): Promise<T | null>;
    set(key: string, value: JsonValue): Promise<null>;
  };
  readonly http: {
    request(options: { url: string }): Promise<{ body: string }>;
  };
}
export type ToolHandler = (arguments_: JsonObject, context: PluginContext) => JsonValue | Promise<JsonValue>;
export interface PluginDefinition {
  tools: Record<string, ToolHandler>;
  activate?(context: PluginContext): void | Promise<void>;
  deactivate?(context: PluginContext): void | Promise<void>;
}
export interface PluginManifest {
  id: string;
  name: string;
  description: string;
  version: string;
  apiVersion: 1;
  permissions: ('storage' | 'network')[];
  dependencies: string[];
  tools: { name: string; description: string; inputSchema: JsonObject }[];
}
export function definePlugin<T extends PluginDefinition>(definition: T): T { return definition; }
export function textArgument(args: JsonObject, key: string, maxLength = 4000): string {
  const value = args[key];
  if (typeof value !== 'string' || !value.trim() || value.length > maxLength) throw new Error(`Invalid ${key}`);
  return value.trim();
}

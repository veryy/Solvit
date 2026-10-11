import { parseDocument } from 'yaml';

export interface SkillDocument {
  name: string;
  description: string;
  body: string;
  license?: string;
  compatibility?: string;
  allowedTools?: string;
}
export function utf8Bytes(text: string): number {
  let bytes = 0;
  for (const char of text) {
    const point = char.codePointAt(0)!;
    bytes += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
  }
  return bytes;
}
export function parseSkillDocument(source: string): SkillDocument {
  if (utf8Bytes(source) > 32 * 1024) throw new Error('SKILL.md exceeds 32 KiB');
  const text = source.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
  const match = /^---\n([\s\S]*?)\n---(?:\n|$)([\s\S]*)$/.exec(text);
  if (!match) throw new Error('SKILL.md requires YAML frontmatter');
  const document = parseDocument(match[1], { schema: 'failsafe', uniqueKeys: true, logLevel: 'silent' });
  if (document.errors.length || document.warnings.length) throw new Error('Invalid skill YAML');
  const metadata: unknown = document.toJS({ maxAliasCount: 0 });
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) throw new Error('Invalid skill metadata');
  const value = metadata as Record<string, unknown>;
  const name = value.name;
  const description = value.description;
  if (typeof name !== 'string' || name.length > 64 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name)) throw new Error('Invalid skill name');
  if (typeof description !== 'string' || !description.trim() || description.length > 1024) throw new Error('Invalid skill description');
  const body = match[2].trim();
  if (!body) throw new Error('Skill instructions are empty');
  const result: SkillDocument = { name, description: description.trim(), body };
  for (const [key, field, limit] of [['license', 'license', 1024], ['compatibility', 'compatibility', 500], ['allowed-tools', 'allowedTools', 1024]] as const) {
    if (value[key] !== undefined) {
      if (typeof value[key] !== 'string' || (value[key] as string).length > limit) throw new Error(`Invalid ${key}`);
      result[field] = value[key] as string;
    }
  }
  if (value.metadata !== undefined && (!value.metadata || typeof value.metadata !== 'object' || Array.isArray(value.metadata) ||
    Object.values(value.metadata).some(item => typeof item !== 'string'))) throw new Error('Invalid skill metadata map');
  return result;
}

export function validateResourcePath(path: string): void {
  const parts = path.split('/');
  if (path.length > 160 || parts.length > 6 || parts.some(part => !part || part === '.' || part === '..') ||
      path.startsWith('/') || /[\\\u0000-\u001f]/.test(path) ||
      !/^(scripts|references|assets)\/.+\.(md|txt|json|csv|yaml|yml|ts|js|py|sh|html|css|xml)$/.test(path)) throw new Error('Invalid text resource path');
}

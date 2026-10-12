import type { PluginContext } from '../../plugin-sdk/src/index';
import type { MemoryDatabase } from './index';
import { transact, failure } from './automatic';
import { sameMemoryFact, type MemoryPageBackend } from './pages';

/** Reuse the database validator and quota-checked writer of the memory plugin. */
export function createMemoryPageBackend(
  read: (context: PluginContext) => Promise<MemoryDatabase>,
  persist: (database: MemoryDatabase, context: PluginContext) => Promise<void>,
): MemoryPageBackend {
  return {
    read,
    save: (input, context) => transact(context, async local => {
      const body = input.text.trim(), category = input.category.trim();
      if (!body || body.length > 4000 || !category || category.length > 40 ||
          input.tags.length > 12 || input.tags.some(tag => !tag.trim() || tag.length > 40))
        throw failure('STORAGE', 'Invalid manual memory');
      const db = await read(local);
      const now = new Date().toISOString();
      let id: string;
      if (input.expected) {
        const current = db.facts.find(fact => fact.id === input.expected!.id);
        // Checked again on every CAS retry, including changes after page rendering.
        if (!sameMemoryFact(current, input.expected)) throw failure('STORAGE', 'Memory changed; reload before saving');
        current!.text = body;
        current!.category = category;
        current!.tags = [...new Set(input.tags.map(tag => tag.trim()))];
        current!.updatedAt = now;
        current!.modifiedByUser = true;
        id = current!.id;
      } else {
        // Explicit creation does not silently merge into another record.
        if (!Number.isSafeInteger(db.sequence + 1)) throw failure('STORAGE', 'Memory sequence exhausted');
        id = `m${++db.sequence}`;
        if (db.facts.some(fact => fact.id === id)) throw failure('STORAGE', 'Memory ID collision');
        db.facts.push({ id, text: body, category, tags: [...new Set(input.tags.map(tag => tag.trim()))],
          createdAt: now, updatedAt: now, modifiedByUser: true });
      }
      await persist(db, local);
      return id;
    }),
    remove: (expected, context) => transact(context, async local => {
      const db = await read(local);
      if (!sameMemoryFact(db.facts.find(fact => fact.id === expected.id), expected))
        throw failure('STORAGE', 'Memory changed; reload before deleting');
      db.facts = db.facts.filter(fact => fact.id !== expected.id);
      db.edges = db.edges.filter(edge => edge.from !== expected.id && edge.to !== expected.id);
      // Keep completedSources receipts: retries must not resurrect a deleted fact.
      await persist(db, local);
    }),
  };
}

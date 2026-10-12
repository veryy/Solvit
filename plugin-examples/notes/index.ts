import { definePlugin, type PluginPageView } from '../../plugin-sdk/src/index';

const labels: Record<string, readonly string[]> = {
  zh: ['便签', '内容', '保存', '删除', '确认删除便签？'],
  en: ['Notes', 'Note', 'Save', 'Delete', 'Delete this note?'],
  ja: ['メモ', '内容', '保存', '削除', 'このメモを削除しますか？'],
  ko: ['메모', '내용', '저장', '삭제', '이 메모를 삭제할까요?'],
  es: ['Notas', 'Contenido', 'Guardar', 'Eliminar', '¿Eliminar esta nota?'],
  fr: ['Notes', 'Contenu', 'Enregistrer', 'Supprimer', 'Supprimer cette note ?'],
};
export default definePlugin({
  tools: {},
  pages: {
    async main(input, ctx): Promise<PluginPageView> {
      if (input.event?.action === 'save') {
        const note = input.event.fields.note;
        if (typeof note !== 'string' || note.length > 4096) throw new Error('Invalid note');
        await ctx.storage.set('note', note);
      } else if (input.event?.action === 'delete') {
        await ctx.storage.set('note', '');
      }
      const text = labels[input.locale.split('-')[0]] ?? labels.zh;
      const stored = await ctx.storage.get('note');
      return {
        title: text[0],
        nodes: [{ id: 'note', kind: 'field', label: text[1], value: typeof stored === 'string' ? stored : '', multiline: true }],
        footer: [
          { id: 'save', kind: 'button', text: text[2], action: 'save' },
          { id: 'delete', kind: 'button', text: text[3], action: 'delete', confirm: text[4] },
        ],
      };
    },
  },
});

import { definePlugin, type PluginContext } from '../../plugin-sdk/src/index';
async function now(ctx: PluginContext) {
  if (ctx.clock) return ctx.clock.now();
  const date = new Date();
  return { utc: date.toISOString(), timezoneOffsetMinutes: date.getTimezoneOffset(), timezone: 'runtime-local (旧宿主未提供 Android 时区)' };
}
export default definePlugin({
  async beforeModel(_input, ctx) {
    const snapshot = await now(ctx);
    return '设备当前时间（每轮聊天开始时重新读取）：' + JSON.stringify(snapshot) +
      '\nlocalTime 是设备本地时间，utc 是同一瞬间的 UTC 时间，不能把两个字段照写成同一个钟表时刻。带 +08:00 的本地时间比 UTC 快八小时。' +
      '\n用户明确询问现在几点、当前时间或今天日期时，请先调用 dev_solvit_clock_current_time，再根据实际工具结果回答；不要猜测，不要把午夜当作默认值。设备时钟可能由用户调整。';
  },
  tools: { async current_time(_args, ctx) { return now(ctx); } },
});

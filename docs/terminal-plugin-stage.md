# Interactive terminal plugin migration (in progress)

Source study: DSH `d743267388641bc76f17c45ce8b4c231aed1d32c`,
`packages/terminal/terminal/src/index.ts` and `tests/service.spec.ts` (exact-owner
operation fencing, creation rollback, owner/service disposal); MIT, no source copied.
Operit `dbf71916fae9750cfdc9f9a774f5a0fee56633fb`,
`core/tools/defaultTool/standard/StandardTerminalCommandExecutor.kt` and
`core/tools/system/Terminal.kt`: named persistent sessions, incremental output,
interrupt and explicit close. Operit is LGPL-3.0; no implementation copied.

Native PTY remains an Android service. The offline HTML/JS plugin will own terminal
rendering, Shell selection and keyboard handling. The host will grant a scoped page
capability after native confirmation, rather than expose arbitrary process handles.

Foundation: terminal identity now includes chat and human/model ownership, so two
chats sharing a workspace cannot reuse each other's page terminals. The native
workspace binding and page authorization are rechecked after queued execution.
Resizing reaches the existing JNI PTY ioctl. Bounded output includes a monotonic
UTF-16 offset and avoids trimming through a surrogate pair. Legacy native callers
retain compatibility; migration of their interactive UI is the next step.

Added a real Android PTY regression for shared-workspace isolation, page grant
revocation, model versus human ownership and `stty size`. Not yet run: remote
validation and an APK are required before delivery. No local build/test was run.

Remaining overall scope stays in `product-roadmap.md`; neither this stage nor test
counts prove actual-model role/streaming/tool selection quality.

UI implementation: xterm.js 5.5.0 and addon-fit 0.10.0, upstream commit
`9ba6c00a195c95fcf8292a2b9084d91450e5daae`. Read
`src/browser/public/Terminal.ts`, `src/browser/Terminal.ts` (DOM/IME input),
`addons/addon-fit/src/FitAddon.ts` and `test/playwright/Terminal.test.ts`
(onData/onResize and buffer rendering). Their official npm distributions are
vendored without running local builds, with SHA-512 registry integrity checked;
MIT LICENSE files are bundled with the page. `terminal-vendor-sources.json` pins
both archives. No CDN/network loading or arbitrary native process bridge.

Read Operit's pinned TerminalCore gitlink `045868988e4091d613cb34b2941057703899b96e`
`view/canvas/CanvasTerminalView.kt` size/surface lifecycle: view geometry must reach
PTY resize and zero-sized/destroyed views must stop interacting. Solvit instead uses
xterm's HTML geometry and Android PTY resize; no Operit source was copied.

API13 adds a generic live terminal page capability, gated by explicit
`terminal.pty`, workspace/shell permissions, installed-package authorization and
the captured chat/workspace. Opening each Shell requires native confirmation.
The grant permits the page to send commands until closed/revoked, and is never
injected into model tool/Hook contexts. Rapid raw input is serialized and bounded.
Closed pages lose control; manual Shells persist until explicit close, execution
identity invalidation or application exit. AI turn cleanup retains its existing
semantics; a full DSH agent lifetime replacement is not claimed.

The interactive workspace tab now embeds the immutable APK HTML plugin even when
AI plugin tools are disabled. Installed/downloaded plugin pages use the same generic
container and scoped capability. Ordinary finite-command history remains native
compatibility UI and is not claimed migrated.

The immutable manual page also retains access to AI-created terminals in the
**same chat**: it lists only scoped session metadata and requires a separate
native confirmation before viewing output or sending input. Confirmed session IDs
are carried in a native page authority and cannot authorize another chat, even
when its workspace is shared. Downloaded pages can only attach their own plugin's
sessions. Model tools never inherit these human grants. Added native and actual
HTML/PTY regressions for this path instead of silently removing the old capability.

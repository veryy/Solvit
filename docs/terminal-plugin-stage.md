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

Remote red evidence: diagnostic `38333681117` executed the first real HTML case;
PTY returned `__UI_READY__` but the rendered terminal did not show it, native size
was 2×48 and the captured WebView area was blank. Candidate `66502f5` passed 304
unit tests/Lint/APK, but full `38335021382` stopped in the new native Unicode output
regression after 8 seconds, before App UI tests. UI anchoring/compact controls are
not yet verified. Separate diagnostic `38335047051` failed while downloading NDK
(`ZipFile unknown archive`), before compilation/testing; it is not a product result.

The reader now batches decoded output in 4K/32ms publications and flushes during
idle native polls and EOF, so continuous tiny writes do not repeatedly copy the
full retained transcript. The same real-PTY 8s regression remains, with bounded
failure diagnostics; no timeout or assertion was weakened. The HTML page retains
an explicit output-gap notice after truncation instead of replacing it on the next
status update. The actual HTML test also attaches and replays a truncated model
terminal before checking the notice and subsequent interactive input.

Further evidence: diagnostic `38336405367` showed a 412×500 CSS viewport and a
16×48 terminal; visible output, environment reuse, raw xterm input, Ctrl-C and
multiple Shells passed before the newly added large-output fixture timed out.
Full `38336309890` still failed the Unicode fixture: offset 0, 514 retained chars,
revision 1, command echo only. This does **not** establish batching as the cause
or a fix for that timeout. The fixtures now prepare the same 68K UTF-16/136K UTF-8
Unicode file through WorkspaceService and stream it with real Android `cat`,
separating output/decoder verification from 34000 interactive shell evaluations.
The original 8s deadline and all Unicode/offset/truncation assertions stay in place.

The default PTY prompt is now `$ ` / `> ` rather than an empty environment value
that Android's shell replaces with a very long directory prompt. The page shows a
localized manual-terminal label. Original byte-identical terminal 1.0.0 packages
upgrade to the HTML bundle while keeping activation and grants; modified packages
and user-uninstalled seeds are preserved. Added restore/upgrade regressions.

Final source `0498389` passes [full Android gate](https://github.com/veryy/Solvit/actions/runs/38337675616): parsed XML 306 unit + 48 emulator, zero failures/errors/skips, Lint/APK/stable certificate. [Focused real HTML case](https://github.com/veryy/Solvit/actions/runs/38337818231) passes in 18.947s. Actual APK and final screenshot are linked in [candidate evidence](release-0.22.3.md). No local build/test was run. The screenshot still shows long Android mksh prompts despite setting PS1 and dense controls; do not claim those visual issues solved.

Production market was backed up at `/opt/solvit-market-backup-api12-before-terminal13`, updated only server/terminal/legacy terminal, and checked through verified public HTTPS: all API1–13 catalogs and 19 selected package versions match their advertised lengths/hashes. API13 new terminal hash is `e05c420559d3f6a14924fbc455cefe71f510844c3f0802a457ff754c3fd2f4d8`, also exactly matches APK asset; API5–12 original terminal remains `d124c4f16262d0d559eb0b45c21c790e79f8ee1aaacfc9b1464397a1520682a3`. First post-deploy curl health command used unsupported retry option; corrected plain curl verified active service and health, then public byte checks passed. Existing other package versions preserved.

Remaining: actual model/phone task acceptance, full DSH-style kernel lifecycle, search publication gate, full-screen TUI/mobile keyboard, terminal polish and native ordinary-command compatibility migration. This is a verified candidate stage, not whole-project completion or formal release.

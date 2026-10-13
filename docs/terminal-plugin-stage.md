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

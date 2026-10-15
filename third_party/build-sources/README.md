# Build source archives

`talloc-2.5.0.tar.gz` is the unmodified upstream source from
https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz (official mirror:
https://download.samba.org/pub/talloc/talloc-2.5.0.tar.gz).

SHA-256: `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007`.

The same source was used in successful Solvit releases, including the fully verified
0.41.0 `solvit-linux-sources.zip`. Both Samba download hosts timed out during the
0.42.0 release attempt. Keeping the original source avoids this network dependency;
Actions still verifies the pinned digest and compiles each Android ABI with the NDK.
This directory contains no compiled executables and is not packaged as an APK asset.

Upstream licenses are inside the archive; the existing distributed notice is
`linux-android/src/main/assets/linux-notices/TALLOC-LICENSE.txt`.
Every published Linux source ZIP continues to contain this exact archive, build script,
cross-compilation answers, PRoot sources/patches and license notices.

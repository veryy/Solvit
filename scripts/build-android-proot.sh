#!/usr/bin/env bash
# Remote-only source build. Standalone executables; no unverified upstream binaries.
set -euo pipefail
: "${ANDROID_SDK_ROOT:?Android SDK required}"
ndk="$ANDROID_SDK_ROOT/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64"
build_dir="${RUNNER_TEMP:?GitHub Actions only}/solvit-android-proot"
mkdir -p "$build_dir"
git clone --no-checkout https://github.com/termux/proot.git "$build_dir/proot-source"
git -C "$build_dir/proot-source" checkout --detach b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087
# Narrow NDK Clang fix: upstream uses strcmp/memset without their declaration.
python3 - "$build_dir/proot-source/src/extension/ashmem_memfd/ashmem_memfd.c" <<'PY_PATCH'
import sys
from pathlib import Path
p = Path(sys.argv[1])
s = p.read_text()
anchor = '#include "tracee/seccomp.h"'
assert s.count(anchor) == 1 and '#include <string.h>' not in s
p.write_text(s.replace(anchor, '#include <string.h>\n' + anchor))
PY_PATCH
curl --fail --location --silent --show-error --retry 2 https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz --output "$build_dir/talloc.tar.gz"
printf '%s\n' "912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007  $build_dir/talloc.tar.gz" | sha256sum --check
for abi in arm64-v8a x86_64; do
    case "$abi" in arm64-v8a) target=aarch64-linux-android;; x86_64) target=x86_64-linux-android;; esac
    abi_dir="$build_dir/$abi"
    mkdir -p "$abi_dir"
    tar -xzf "$build_dir/talloc.tar.gz" -C "$abi_dir"
    cp -R "$build_dir/proot-source" "$abi_dir/proot"
    export CC="$ndk/bin/${target}26-clang" AR="$ndk/bin/llvm-ar" RANLIB="$ndk/bin/llvm-ranlib"
    (
        cd "$abi_dir/talloc-2.5.0"
        # Waf cross answers avoid attempting to execute guest ELFs on the host.
        cat > cross-answers.txt <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: FAIL
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS
        ./configure --prefix="$abi_dir/prefix" --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt
        make -j2
        "$AR" rcs "$abi_dir/libtalloc.a" bin/default/talloc*.o
    )
    make -C "$abi_dir/proot/src" -j2 CC="$CC" STRIP="$ndk/bin/llvm-strip" \
        OBJCOPY="$ndk/bin/llvm-objcopy" OBJDUMP="$ndk/bin/llvm-objdump" \
        HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/not-used \
        CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -I. -I$abi_dir/proot/src -I$abi_dir/talloc-2.5.0" \
        LDFLAGS="$abi_dir/libtalloc.a -Wl,-z,noexecstack -Wl,-z,max-page-size=16384"
    destination="linux-probe/src/main/jniLibs/$abi"
    mkdir -p "$destination"
    cp "$abi_dir/proot/src/proot" "$destination/libsolvit_proot.so"
    cp "$abi_dir/proot/src/loader/loader" "$destination/libsolvit_loader.so"
    "$ndk/bin/llvm-readelf" -h "$destination/libsolvit_proot.so"
    sha256sum "$destination/"*.so
    # GPL source, license and build inputs accompany diagnostic artifacts.
    mkdir -p "linux-probe/build-inputs/$abi"
    cp "$abi_dir/talloc-2.5.0/cross-answers.txt" "linux-probe/build-inputs/$abi/"
done
git -C "$build_dir/proot-source" diff > linux-probe/build-inputs/ndk-string-header.patch
cp "$build_dir/proot-source/COPYING" linux-probe/build-inputs/PROOT-COPYING
cp "$build_dir/talloc.tar.gz" linux-probe/build-inputs/
git -C "$build_dir/proot-source" archive --format=tar.gz HEAD > linux-probe/build-inputs/proot-source.tar.gz
cp scripts/build-android-proot.sh linux-probe/build-inputs/

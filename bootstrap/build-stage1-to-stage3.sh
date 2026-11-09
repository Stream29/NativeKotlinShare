#!/usr/bin/env bash
set -Eeuo pipefail
: "${WORK_ROOT:?}" "${DEPS_ROOT:?}" "${OUTPUT_DIR:?}" "${BASELINE_ROOT:?}" "${JAVA_HOME:?}"
mode=${1:-all}
case "$mode" in all|oracle|klib|library|native) ;; *) exit 64 ;; esac
WORK_ROOT=$(realpath "$WORK_ROOT")
DEPS_ROOT=$(realpath "$DEPS_ROOT")
BASELINE_ROOT=$(realpath "$BASELINE_ROOT")
JAVA_HOME=$(realpath "$JAVA_HOME")
OUTPUT_DIR=$(realpath -m "$OUTPUT_DIR")
test ! -e "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"
cd "$WORK_ROOT"
upstream="$BASELINE_ROOT/kotlin"
dist="$upstream/kotlin-native/dist"
test "$(git -C "$upstream" rev-parse HEAD)" = 9180ce6a7a7d3627f88b424717583f28ddca052d
sha256sum --quiet -c bootstrap/manifests/production.sha256
(cd "$BASELINE_ROOT"; sha256sum --quiet -c "$WORK_ROOT/bootstrap/manifests/toolchain.sha256")
(cd "$JAVA_HOME"; sha256sum --quiet -c "$WORK_ROOT/bootstrap/manifests/java.sha256")
(cd "$DEPS_ROOT"; sha256sum --quiet -c "$WORK_ROOT/bootstrap/manifests/dependencies.sha256")

export JAVA_HOME PATH="$JAVA_HOME/bin:/usr/bin:/bin" LC_ALL=C TZ=UTC
export JAVA_OPTS="${NATIVEKOTLIN_JAVA_OPTS:--Xmx${NATIVEKOTLIN_MAX_HEAP:-768m} -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:ActiveProcessorCount=${NATIVEKOTLIN_CPU_COUNT:-1}}"
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS LD_PRELOAD LD_LIBRARY_PATH
unset CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH LIBRARY_PATH
read -r -a java_flags <<< "$JAVA_OPTS"
printf 'MODE=%s\nJAVA_OPTS=%s\nCPU_COUNT=%s\n' "$mode" "$JAVA_OPTS" "${NATIVEKOTLIN_CPU_COUNT:-1}" \
    > "$OUTPUT_DIR/resource-profile.txt"

sha256sum bootstrap/manifests/{production.sha256,dependencies.sha256,toolchain.sha256,java.sha256,native-sources.list,build.sh,sources.tsv} \
    bootstrap/tests/E1/{E1Cases.kt,E1Jvm.kt,E1Native.kt} \
    bootstrap/tests/E1/fixtures/{BaseLibA.kt,BaseLibB.kt,AppMain.kt,BrokenSource.kt,e1_c_sample.h,e1_c_sample.c,e1_c_sample.def,mini_base.def,mini_ext.def} \
    bootstrap/tests/D1/{D1CoreLib.kt,D1Cases.kt,D1Jvm.kt,D1Native.kt} \
    bootstrap/tests/D1/c/{d1_c_bridge.h,d1_c_bridge.c,d1cbridge.def} \
    "$OUTPUT_DIR/resource-profile.txt" > "$OUTPUT_DIR/input-fingerprint.sha256"

mapfile -t s1_sources < bootstrap/manifests/native-sources.list
test "${#s1_sources[@]}" -eq 9
sha256sum "${s1_sources[@]}" \
    bootstrap/tests/E1/{E1Cases.kt,E1Jvm.kt,E1Native.kt} \
    bootstrap/tests/D1/{D1CoreLib.kt,D1Cases.kt,D1Jvm.kt,D1Native.kt} \
    bootstrap/tests/D1/c/{d1_c_bridge.h,d1_c_bridge.c,d1cbridge.def} \
    > "$OUTPUT_DIR/selected-sources.sha256"

export KONAN_DATA_DIR="$OUTPUT_DIR/konan-home"
export TMPDIR="$OUTPUT_DIR/tmp"
cleanup_tmpdir() {
    rm -rf "$TMPDIR"
}
trap cleanup_tmpdir EXIT
mkdir -p "$KONAN_DATA_DIR/dependencies" "$TMPDIR"
for dependency in llvm-21-x86_64-linux-essentials-116 llvm-21-x86_64-linux-dev-116 lldb-4-linux \
    libffi-3.2.1-2-linux-x86-64 x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2; do
    test -d "$BASELINE_ROOT/konan-home/dependencies/$dependency"
    ln -s "$BASELINE_ROOT/konan-home/dependencies/$dependency" "$KONAN_DATA_DIR/dependencies/$dependency"
done
cp "$BASELINE_ROOT/konan-home/dependencies/.extracted" "$KONAN_DATA_DIR/dependencies/.extracted"

llvm="$BASELINE_ROOT/konan-home/dependencies/llvm-21-x86_64-linux-dev-116"
toolchain="$BASELINE_ROOT/konan-home/dependencies/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2"
konanc_stage0="$dist/bin/konanc"
compiler="$dist/konan/lib/kotlin-native-compiler-embeddable.jar"
stage1_cli="$DEPS_ROOT/build/e1c/konanc-cli.kexe"
stage1_e1c_klib="$DEPS_ROOT/build/e1c/e1c-cli-native.klib"
test -x "$stage1_cli"
test -s "$stage1_e1c_klib"

# Locate D1's verified rebuilt distribution
d1_rebuilt_dist=""
if [[ -d "$DEPS_ROOT/build/d1/rebuilt-dist/klib/common/stdlib" ]]; then
    d1_rebuilt_dist="$DEPS_ROOT/build/d1/rebuilt-dist"
else
    for cand in $(ls -dt "$DEPS_ROOT"/build/builtin-wave6/D1-*/build/result/rebuilt-dist 2>/dev/null || true); do
        if [[ -d "$cand/klib/common/stdlib" && -d "$cand/klib/cache/linux_x64-gSTATIC-system/stdlib-cache" ]]; then
            d1_rebuilt_dist="$cand"
            break
        fi
    done
fi
test -n "$d1_rebuilt_dist"
test -d "$d1_rebuilt_dist/klib/common/stdlib"
test -d "$d1_rebuilt_dist/klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix"
test -d "$d1_rebuilt_dist/klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.zlib"
test -s "$d1_rebuilt_dist/klib/cache/linux_x64-gSTATIC-system/stdlib-cache/bin/libstdlib-cache.a"

drop_cache() {
    python3 - "$OUTPUT_DIR" "$@" <<'PY'
import os, sys
out_dir = os.path.realpath(sys.argv[1]) if len(sys.argv) > 1 else ""
def evict(p):
    try:
        if os.path.islink(p) or not os.path.isfile(p):
            return
        fd = os.open(p, os.O_RDONLY | os.O_NONBLOCK)
        try:
            if out_dir and p.startswith(out_dir):
                try:
                    os.fsync(fd)
                except OSError:
                    pass
            os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
        finally:
            os.close(fd)
    except OSError:
        pass
for arg in sys.argv[2:]:
    if os.path.isfile(arg):
        evict(arg)
    elif os.path.isdir(arg) and not os.path.islink(arg):
        for root, _, files in os.walk(arg):
            for f in files:
                evict(os.path.join(root, f))
PY
}

run() {
    local name=$1
    shift
    printf '%q ' "$@" > "$OUTPUT_DIR/$name.command"
    printf '\n' >> "$OUTPUT_DIR/$name.command"
    set +e
    "$@" > "$OUTPUT_DIR/$name.log" 2>&1
    local status=$?
    set -e
    printf '%s\n' "$status" > "$OUTPUT_DIR/$name.exit"
    drop_cache "$compiler" "$dist/konan" "$JAVA_HOME/lib/modules" "$stage1_cli" "$d1_rebuilt_dist" "$OUTPUT_DIR"
    if [[ "$status" -ne 0 ]]; then
        cat "$OUTPUT_DIR/$name.log" >&2
        return "$status"
    fi
}

runtime_gate() {
    local bin=$1
    local name=$2
    local home_dist=$3
    shift 3
    test -x "$bin"
    readelf -lW "$bin" > "$OUTPUT_DIR/$name.program-headers.txt"
    readelf -dW "$bin" > "$OUTPUT_DIR/$name.dynamic.txt"
    env -i PATH=/usr/bin:/bin LC_ALL=C ldd -r "$bin" > "$OUTPUT_DIR/$name.loader.txt" 2>&1
    if grep -Ei 'libjvm|libjava|libjli|jvm\.dll|clangstubs|callbacks|not found|undefined symbol' \
        "$OUTPUT_DIR/$name.program-headers.txt" "$OUTPUT_DIR/$name.dynamic.txt" "$OUTPUT_DIR/$name.loader.txt"; then
        echo "Dynamic loader gate failed for $bin" >&2
        cat "$OUTPUT_DIR/$name.loader.txt" >&2
        return 1
    fi
    awk '/=> \// {print $3} /^[[:space:]]*\// {print $1}' "$OUTPUT_DIR/$name.loader.txt" | sort -u > "$TMPDIR/$name.loaded-libs.list"
    xargs -r sha256sum < "$TMPDIR/$name.loaded-libs.list" > "$OUTPUT_DIR/$name.loaded-libraries.sha256"
    while IFS= read -r lib_p; do
        drop_cache "$lib_p"
    done < "$TMPDIR/$name.loaded-libs.list"
    rm -f "$TMPDIR/$name.loaded-libs.list"
    drop_cache "$bin" "$d1_rebuilt_dist" "$OUTPUT_DIR"
    run "$name.run" timeout 900 env -i \
        PATH=/nonexistent \
        HOME="$OUTPUT_DIR" \
        TMPDIR="$TMPDIR" \
        KONAN_DATA_DIR="$KONAN_DATA_DIR" \
        KONAN_HOME="$home_dist" \
        KOTLIN_NATIVE_HOME="$home_dist" \
        MALLOC_ARENA_MAX=1 \
        MALLOC_TRIM_THRESHOLD_=65536 \
        MALLOC_MMAP_THRESHOLD_=65536 \
        LC_ALL=C \
        TZ=UTC \
        "$bin" "$@"
}

# Prepare fixtures for E1 & D1 regression suites
fixtures_dir="$TMPDIR/e1-fixtures"
c_lib_dir="$TMPDIR/e1-c-lib"
d1_c_dir="$TMPDIR/d1-c-lib"
mkdir -p "$fixtures_dir" "$c_lib_dir" "$d1_c_dir"
cp bootstrap/tests/E1/fixtures/* "$fixtures_dir/"
printf 'INVALID_BITCODE_16B\n' > "$fixtures_dir/bad.bc"

run c-fixture-compile env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
    "$llvm/bin/clang" -target x86_64-unknown-linux-gnu -nostdinc \
    -I"$fixtures_dir" -O2 -c "$fixtures_dir/e1_c_sample.c" -o "$c_lib_dir/e1_c_sample.o"
run c-fixture-archive env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
    "$llvm/bin/llvm-ar" rcs "$c_lib_dir/libe1_c_sample.a" "$c_lib_dir/e1_c_sample.o"
test -s "$c_lib_dir/libe1_c_sample.a"

sysroot="$toolchain/x86_64-unknown-linux-gnu/sysroot"
run d1-c-compile env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
    "$llvm/bin/clang" \
    -B"$toolchain/x86_64-unknown-linux-gnu/bin" \
    --gcc-toolchain="$toolchain" \
    -target x86_64-unknown-linux-gnu \
    --sysroot="$sysroot" \
    -fPIC -O2 -c "$WORK_ROOT/bootstrap/tests/D1/c/d1_c_bridge.c" \
    -I"$WORK_ROOT/bootstrap/tests/D1/c" \
    -o "$d1_c_dir/d1_c_bridge.o"
run d1-c-archive env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
    "$llvm/bin/llvm-ar" rcs "$d1_c_dir/libd1_c_bridge.a" "$d1_c_dir/d1_c_bridge.o"
test -s "$d1_c_dir/libd1_c_bridge.a"

run_jvm_oracles() {
    export MALLOC_ARENA_MAX=1 MALLOC_TRIM_THRESHOLD_=65536 MALLOC_MMAP_THRESHOLD_=65536
    export JAVA_OPTS="-Xmx768m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=20 -XX:ActiveProcessorCount=${NATIVEKOTLIN_CPU_COUNT:-1}"
    read -r -a oracle_java_flags <<< "$JAVA_OPTS"
    run oracle-e1-compile "$JAVA_HOME/bin/java" "${oracle_java_flags[@]}" -cp "$compiler" \
        -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$compiler" \
        -XXLanguage:+NameBasedDestructuring -Xcontext-parameters \
        -opt-in=org.jetbrains.kotlin.K1Deprecation \
        -opt-in=org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi \
        -opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi \
        -opt-in=org.jetbrains.kotlin.config.CompilerConfiguration.Internals \
        -opt-in=org.jetbrains.kotlin.config.MessageCollectorAccess \
        bootstrap/tests/E1/E1Cases.kt bootstrap/tests/E1/E1Jvm.kt \
        -d "$TMPDIR/e1-oracle.jar"
    rm -rf "$TMPDIR/jvm-work" "$TMPDIR/jvm-tmp"
    mkdir -p "$TMPDIR/jvm-work" "$TMPDIR/jvm-tmp"
    run oracle-e1 env KONAN_HOME="$dist" KONAN_DATA_DIR="$KONAN_DATA_DIR" \
        "$JAVA_HOME/bin/java" "${oracle_java_flags[@]}" -ea \
        "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" \
        "-Dkonan.home=$dist" "-Dkotlin.native.home=$dist" \
        "-Djava.library.path=$dist/konan/nativelib" \
        "-Djava.io.tmpdir=$TMPDIR/jvm-tmp" \
        -cp "$TMPDIR/e1-oracle.jar:$compiler" \
        nativekotlin.e1.test.E1JvmKt \
        "$fixtures_dir" "$c_lib_dir" "$fixtures_dir/bad.bc" \
        "$TMPDIR/jvm-work" "$dist" "$KONAN_DATA_DIR" "$llvm"
    grep -Fx 'E1_CLI_SUITE_OK' "$OUTPUT_DIR/oracle-e1.log"
    runtime_gate "$TMPDIR/jvm-work/two-stage-app.kexe" jvm-two-stage-app "$dist"
    grep -Fx 'E1_STAGE_APP_OK' "$OUTPUT_DIR/jvm-two-stage-app.run.log"
    runtime_gate "$TMPDIR/jvm-work/one-stage-app.kexe" jvm-one-stage-app "$dist"
    grep -Fx 'E1_STAGE_APP_OK' "$OUTPUT_DIR/jvm-one-stage-app.run.log"
    rm -rf "$TMPDIR/e1-oracle.jar" "$TMPDIR/jvm-work" "$TMPDIR/jvm-tmp"

    # Build & run D1 JVM oracle for the 7-section stdlib/runtime/cinterop/POSIX/zlib corpus
    local d1_jvm_dir="$TMPDIR/d1-jvm"
    mkdir -p "$d1_jvm_dir"
    run oracle-d1-core-klib "$konanc_stage0" \
        -kotlin-home "$d1_rebuilt_dist" \
        "-Xkonan-data-dir=$KONAN_DATA_DIR" \
        -target linux_x64 \
        -produce library \
        -no-default-libs \
        -Xmulti-platform \
        "-Xcommon-sources=$WORK_ROOT/bootstrap/tests/D1/D1CoreLib.kt" \
        -ea \
        -Xpartial-linkage-loglevel=error \
        "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvm" \
        -module-name org.jetbrains.kotlin.native.d1.core \
        -o "$d1_jvm_dir/d1-core-lib.klib" \
        "$WORK_ROOT/bootstrap/tests/D1/D1CoreLib.kt"
    run oracle-d1-core-abi "$JAVA_HOME/bin/java" -ea "${oracle_java_flags[@]}" \
        -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        "-Dkotlin.native.home=$d1_rebuilt_dist" \
        -cp "$compiler" \
        org.jetbrains.kotlin.cli.utilities.MainKt \
        klib dump-abi "$d1_jvm_dir/d1-core-lib.klib"
    cp -f "$OUTPUT_DIR/oracle-d1-core-abi.log" "$OUTPUT_DIR/d1-core-abi.jvm.txt"
    run oracle-d1-compile "$JAVA_HOME/bin/java" "${oracle_java_flags[@]}" \
        -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -cp "$compiler" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect \
        -classpath "$compiler" \
        -d "$d1_jvm_dir/d1-oracle.jar" \
        "$WORK_ROOT/bootstrap/tests/D1/D1CoreLib.kt" \
        "$WORK_ROOT/bootstrap/tests/D1/D1Cases.kt" \
        "$WORK_ROOT/bootstrap/tests/D1/D1Jvm.kt"
    run oracle-d1 "$JAVA_HOME/bin/java" -ea "${oracle_java_flags[@]}" \
        -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -Duser.language=en -Duser.country=US -Duser.timezone=UTC \
        -cp "$d1_jvm_dir/d1-oracle.jar:$compiler" \
        org.jetbrains.kotlin.native.d1.verify.D1JvmKt \
        "$d1_rebuilt_dist" "$OUTPUT_DIR/d1-core-abi.jvm.txt"
    rm -rf "$d1_jvm_dir"
}

if [[ "$mode" == oracle ]]; then
    run_jvm_oracles
    find "$OUTPUT_DIR" -type f ! -path '*/konan-home/*' ! -path '*/tmp/*' ! -name artifacts.sha256 \
        -print0 | sort -z | xargs -0 sha256sum > "$OUTPUT_DIR/artifacts.sha256"
    exit 0
fi

# Common flags & libraries for compiler self-compilation
flags=(
    -target linux_x64
    -ea
    -Xpartial-linkage-loglevel=error
    "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvm"
    "-Xkonan-data-dir=$KONAN_DATA_DIR"
    -kotlin-home "$d1_rebuilt_dist"
    -XXLanguage:+NameBasedDestructuring
    -Xcontext-parameters
    -opt-in=org.jetbrains.kotlin.K1Deprecation
    -opt-in=org.jetbrains.kotlin.backend.konan.InternalKotlinNativeApi
    -opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
    -opt-in=org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
    -opt-in=org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
    -opt-in=org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
    -opt-in=kotlinx.cinterop.ExperimentalForeignApi
    -opt-in=kotlin.ExperimentalUnsignedTypes
    -opt-in=kotlin.experimental.ExperimentalNativeApi
    -opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi
    -opt-in=kotlin.contracts.ExperimentalContracts
    -opt-in=org.jetbrains.kotlin.config.CompilerConfiguration.Internals
    -opt-in=org.jetbrains.kotlin.config.MessageCollectorAccess
    -opt-in=org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
    -opt-in=org.jetbrains.kotlin.psi.KtImplementationDetail
    -opt-in=org.jetbrains.kotlin.psi.KtExperimentalApi
    -opt-in=org.jetbrains.kotlin.psi.KtNonPublicApi
    -opt-in=org.jetbrains.kotlin.types.TypeRefinement
    -opt-in=org.jetbrains.kotlin.types.model.K2Only
    -opt-in=org.jetbrains.kotlin.builtins.functions.AllowedToUsedOnlyInK1
)
printf '%s\n' "${flags[@]}" > "$OUTPUT_DIR/native-flags.txt"

platform_libs=(
    -library "$d1_rebuilt_dist/klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.posix"
    -library "$d1_rebuilt_dist/klib/platform/linux_x64/org.jetbrains.kotlin.native.platform.zlib"
)
libs=("${platform_libs[@]}")
while read -r sha path; do
    if [[ "$path" == *.klib && "$path" != */e1c-cli-native.klib ]]; then
        libs+=(-library "$DEPS_ROOT/$path")
    fi
done < bootstrap/manifests/dependencies.sha256

s1_stage_cache="$DEPS_ROOT/build/s1/stage-cache"
mkdir -p "$s1_stage_cache" "$OUTPUT_DIR/stage2" "$OUTPUT_DIR/stage3"

# Step 1: Build stage2/e1c-cli-native.klib using stage1 under env -i PATH=/nonexistent
stage1_fp=$(sha256sum "$stage1_cli" | awk '{print $1}')
if [[ -s "$s1_stage_cache/stage2-klib.host-fp" ]] && \
   [[ "$(cat "$s1_stage_cache/stage2-klib.host-fp")" == "$stage1_fp" ]] && \
   [[ -s "$s1_stage_cache/stage2/e1c-cli-native.klib" ]]; then
    cp -a "$s1_stage_cache/stage2/e1c-cli-native.klib" "$OUTPUT_DIR/stage2/e1c-cli-native.klib"
    for f in "$s1_stage_cache"/stage1-build-stage2-klib.*; do
        [[ -e "$f" ]] && cp -a "$f" "$OUTPUT_DIR/"
    done
else
    runtime_gate "$stage1_cli" stage1-build-stage2-klib "$d1_rebuilt_dist" \
        konanc "${flags[@]}" "${libs[@]}" "${s1_sources[@]}" \
        -module-name e1c-cli-native -produce library -o "$OUTPUT_DIR/stage2/e1c-cli-native"
    test -e "$OUTPUT_DIR/stage2/e1c-cli-native.klib"
    mkdir -p "$s1_stage_cache/stage2"
    cp -a "$OUTPUT_DIR/stage2/e1c-cli-native.klib" "$s1_stage_cache/stage2/e1c-cli-native.klib"
    for f in "$OUTPUT_DIR"/stage1-build-stage2-klib.*; do
        [[ -e "$f" ]] && cp -a "$f" "$s1_stage_cache/"
    done
    printf '%s\n' "$stage1_fp" > "$s1_stage_cache/stage2-klib.host-fp"
fi

if [[ "$mode" == klib || "$mode" == library ]]; then
    find "$OUTPUT_DIR" -type f ! -path '*/konan-home/*' ! -path '*/tmp/*' ! -name artifacts.sha256 \
        -print0 | sort -z | xargs -0 sha256sum > "$OUTPUT_DIR/artifacts.sha256"
    exit 0
fi

# Link flags for compiler executable
host_cxx=/usr/lib/x86_64-linux-gnu/libstdc++.so.6
gmp=/usr/lib/x86_64-linux-gnu/libgmp.so.10.5.0
icuuc=/usr/lib/x86_64-linux-gnu/libicuuc.so.78
icui18n=/usr/lib/x86_64-linux-gnu/libicui18n.so.78
icudata=/usr/lib/x86_64-linux-gnu/libicudata.so.78
l0_dir="$DEPS_ROOT/build/builtin-dispatch/L0-03/build/l0-native"
read -r -a llvm_libs <<<"$("$llvm/bin/llvm-config" --libfiles analysis bitreader bitwriter core linker target ipo instrumentation lto arm aarch64 x86)"
read -r -a sys_libs <<<"$("$llvm/bin/llvm-config" --system-libs)"
link_override="airplaneMode=true;llvmHome.linux_x64=$llvm;linkerKonanFlags.linux_x64=-Bdynamic $host_cxx -ldl -lm -lpthread -lz -lrt --allow-shlib-undefined --allow-multiple-definition --defsym __cxa_demangle=Konan_cxa_demangle --gc-sections"
link_flags=(
    -linker-option "$gmp"
    -linker-option "$icui18n"
    -linker-option "$icuuc"
    -linker-option "$icudata"
    -linker-option "$l0_dir/libdebugInfo.a"
    -linker-option "$l0_dir/libllvmext.a"
    -linker-option "$llvm/lib/libclang.so"
    -linker-option "-rpath=$llvm/lib"
)
for lib in "${llvm_libs[@]}" "${sys_libs[@]}"; do
    link_flags+=(-linker-option "$lib")
done
link_flags+=("-Xoverride-konan-properties=$link_override")

sys_cache="$d1_rebuilt_dist/klib/cache/linux_x64-gSTATIC-system"
deps_cache="$DEPS_ROOT/build/klib-cache/linux_x64-gSTATIC"
test -d "$sys_cache"
test -d "$deps_cache"
klib_cache_flags=(
    "-Xcached-library=$d1_rebuilt_dist/klib/common/stdlib,$sys_cache/stdlib-cache"
    "-Xcache-directory=$sys_cache"
    "-Xcache-directory=$deps_cache"
)

build_compiler_stage() {
    local host_cli=$1
    local stage_label=$2
    local stage_dir=$3
    local stage_klib=$4

    local host_fp
    host_fp=$(sha256sum "$host_cli" | awk '{print $1}')
    if [[ -s "$s1_stage_cache/${stage_label}.host-fp" ]] && \
       [[ "$(cat "$s1_stage_cache/${stage_label}.host-fp")" == "$host_fp" ]] && \
       [[ -x "$s1_stage_cache/$stage_label/konanc-cli.kexe" ]] && \
       [[ -s "$s1_stage_cache/$stage_label/e1c-cli-native.klib" ]]; then
        mkdir -p "$stage_dir"
        cp -a "$s1_stage_cache/$stage_label/." "$stage_dir/"
        for f in "$s1_stage_cache"/${stage_label}-* "$s1_stage_cache"/stage1-build-stage2-klib.*; do
            [[ -e "$f" ]] && cp -a "$f" "$OUTPUT_DIR/"
        done
        return 0
    fi

    local tmp_bc="$TMPDIR/${stage_label}-bc"
    local deps_file="$TMPDIR/${stage_label}-deps.txt"
    rm -rf "$tmp_bc" "$deps_file"
    mkdir -p "$tmp_bc"
    : > "$tmp_bc/konanc-cli.kexe.o"

    if [[ ! -e "$stage_klib" ]]; then
        runtime_gate "$host_cli" "${stage_label}-klib" "$d1_rebuilt_dist" \
            konanc "${flags[@]}" "${libs[@]}" "${s1_sources[@]}" \
            -module-name e1c-cli-native -produce library -o "${stage_klib%.klib}"
        test -e "$stage_klib"
    fi

    runtime_gate "$host_cli" "${stage_label}-bc" "$d1_rebuilt_dist" \
        konanc "${flags[@]}" "${libs[@]}" "${link_flags[@]}" "${klib_cache_flags[@]}" \
        -library "$stage_klib" \
        -Xbinary=preCodegenInlineThreshold=0 \
        "-Xtemporary-files-dir=$tmp_bc" \
        "-Xwrite-dependencies-to=$deps_file" \
        -Xdisable-phases=CopyDefaultValuesToActual,BuildDFG,DCEPhase,PropertyAccessorInline,InlineClassPropertyAccessorsLowering,DevirtualizationAnalysis,RemoveRedundantCallsToStaticInitializersPhase,Devirtualization,UnboxInline,GHAPhase,EscapeAnalysis,StackProtectorPhase,MandatoryBitcodeLLVMPostprocessingPhase,ModuleBitcodeOptimization,LTOBitcodeOptimization,RemoveRedundantSafepoints,VerifyBitcode,ObjectFiles,Linker \
        bootstrap/tests/E1/E1Cases.kt bootstrap/tests/E1/E1Native.kt \
        -entry nativekotlin.e1.test.main -o "$stage_dir/konanc-cli"
    test -s "$tmp_bc/out.bc"
    test -s "$deps_file"

    run "${stage_label}-bcanalyzer" env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
        "$llvm/bin/llvm-bcanalyzer" "$tmp_bc/out.bc"
    env -i PATH=/nonexistent LC_ALL=C TZ=UTC "$llvm/bin/llvm-nm" --defined-only "$tmp_bc/out.bc" \
        | awk '{print $2, $3}' | LC_ALL=C sort > "$OUTPUT_DIR/${stage_label}-out-bc.symbols.txt"
    sha256sum "$tmp_bc/out.bc" | awk '{print $1}' > "$OUTPUT_DIR/${stage_label}-out-bc.sha256"

    run "${stage_label}-obj" env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
        "$llvm/bin/clang++" \
        -cc1 -emit-obj -disable-llvm-optzns -x ir -triple x86_64-unknown-linux-gnu -O0 -mrelocation-model pic \
        "$tmp_bc/out.bc" -o "$tmp_bc/konanc-cli.kexe.o"
    rm -f "$tmp_bc/out.bc"
    test -s "$tmp_bc/konanc-cli.kexe.o"

    env -i PATH=/nonexistent LC_ALL=C TZ=UTC "$llvm/bin/llvm-as" < /dev/null -o "$tmp_bc/empty.bc"
    runtime_gate "$host_cli" "${stage_label}-link" "$d1_rebuilt_dist" \
        konanc "${flags[@]}" "${libs[@]}" "${link_flags[@]}" "${klib_cache_flags[@]}" \
        -library "$stage_klib" \
        "-Xtemporary-files-dir=$tmp_bc" \
        "-Xcompile-from-bitcode=$tmp_bc/empty.bc" \
        "-Xread-dependencies-from=$deps_file" \
        -Xdisable-phases=StackProtectorPhase,MandatoryBitcodeLLVMPostprocessingPhase,ModuleBitcodeOptimization,LTOBitcodeOptimization,RemoveRedundantSafepoints,WriteBitcodeFile,ObjectFiles \
        -o "$stage_dir/konanc-cli"
    rm -rf "$tmp_bc" "$deps_file"
    test -x "$stage_dir/konanc-cli.kexe"

    rm -rf "$s1_stage_cache/$stage_label"
    mkdir -p "$s1_stage_cache/$stage_label"
    cp -a "$stage_dir/." "$s1_stage_cache/$stage_label/"
    for f in "$OUTPUT_DIR"/${stage_label}-* "$OUTPUT_DIR"/stage1-build-stage2-klib.*; do
        [[ -e "$f" ]] && cp -a "$f" "$s1_stage_cache/"
    done
    printf '%s\n' "$host_fp" > "$s1_stage_cache/${stage_label}.host-fp"
}

# Build stage2 (hosted by stage1) and stage3 (hosted by stage2) without JVM
build_compiler_stage "$stage1_cli" "stage2" "$OUTPUT_DIR/stage2" "$OUTPUT_DIR/stage2/e1c-cli-native.klib"
build_compiler_stage "$OUTPUT_DIR/stage2/konanc-cli.kexe" "stage3" "$OUTPUT_DIR/stage3" "$OUTPUT_DIR/stage3/e1c-cli-native.klib"

# Compare stage1, stage2, and stage3 KLIB ABI, bitcode symbols, and executable symbols
runtime_gate "$OUTPUT_DIR/stage3/konanc-cli.kexe" stage1-klib-abi "$d1_rebuilt_dist" \
    klib dump-abi "$stage1_e1c_klib" -signature-version 2
runtime_gate "$OUTPUT_DIR/stage3/konanc-cli.kexe" stage2-klib-abi "$d1_rebuilt_dist" \
    klib dump-abi "$OUTPUT_DIR/stage2/e1c-cli-native.klib" -signature-version 2
runtime_gate "$OUTPUT_DIR/stage3/konanc-cli.kexe" stage3-klib-abi "$d1_rebuilt_dist" \
    klib dump-abi "$OUTPUT_DIR/stage3/e1c-cli-native.klib" -signature-version 2

run stage1-stage2-klib-abi-diff diff -u "$OUTPUT_DIR/stage1-klib-abi.run.log" "$OUTPUT_DIR/stage2-klib-abi.run.log"
run stage2-stage3-klib-abi-diff diff -u "$OUTPUT_DIR/stage2-klib-abi.run.log" "$OUTPUT_DIR/stage3-klib-abi.run.log"
run stage2-stage3-bc-symbols-diff diff -u "$OUTPUT_DIR/stage2-out-bc.symbols.txt" "$OUTPUT_DIR/stage3-out-bc.symbols.txt"

env -i PATH=/nonexistent LC_ALL=C TZ=UTC "$llvm/bin/llvm-nm" --defined-only "$OUTPUT_DIR/stage2/konanc-cli.kexe" \
    | awk '{print $2, $3}' | LC_ALL=C sort > "$OUTPUT_DIR/stage2-exe.symbols.txt"
env -i PATH=/nonexistent LC_ALL=C TZ=UTC "$llvm/bin/llvm-nm" --defined-only "$OUTPUT_DIR/stage3/konanc-cli.kexe" \
    | awk '{print $2, $3}' | LC_ALL=C sort > "$OUTPUT_DIR/stage3-exe.symbols.txt"
run stage2-stage3-exe-symbols-diff diff -u "$OUTPUT_DIR/stage2-exe.symbols.txt" "$OUTPUT_DIR/stage3-exe.symbols.txt"

{
    printf 'stage\tbinary_sha256\tbinary_bytes\tklib_abi_sha256\tbc_sha256\tsymbol_count\n'
    s1_sha=$(sha256sum "$stage1_cli" | awk '{print $1}')
    s1_sz=$(stat -c '%s' "$stage1_cli")
    s1_abi=$(sha256sum "$OUTPUT_DIR/stage1-klib-abi.run.log" | awk '{print $1}')
    printf 'stage1\t%s\t%s\t%s\tfrom_e1\t%s\n' "$s1_sha" "$s1_sz" "$s1_abi" "$(wc -l < "$OUTPUT_DIR/stage2-exe.symbols.txt")"
    for st in stage2 stage3; do
        exe="$OUTPUT_DIR/$st/konanc-cli.kexe"
        b_sha=$(sha256sum "$exe" | awk '{print $1}')
        b_sz=$(stat -c '%s' "$exe")
        a_sha=$(sha256sum "$OUTPUT_DIR/${st}-klib-abi.run.log" | awk '{print $1}')
        c_sha=$(cat "$OUTPUT_DIR/${st}-out-bc.sha256")
        sym_n=$(wc -l < "$OUTPUT_DIR/${st}-exe.symbols.txt")
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$st" "$b_sha" "$b_sz" "$a_sha" "$c_sha" "$sym_n"
    done
} > "$OUTPUT_DIR/bootstrap-stages-report.tsv"

# Run JVM Oracles (E1 & D1 suites)
run_jvm_oracles

# Run full E1 CLI/frontend/backend/diagnostics/cinterop/platformLibs regression suite on both stage2 and stage3
for st in stage2 stage3; do
    st_work="$TMPDIR/${st}-e1-work"
    rm -rf "$st_work"
    mkdir -p "$st_work"
    runtime_gate "$OUTPUT_DIR/$st/konanc-cli.kexe" "${st}-e1-suite" "$d1_rebuilt_dist" \
        --e1-suite "$fixtures_dir" "$c_lib_dir" "$fixtures_dir/bad.bc" \
        "$st_work" "$d1_rebuilt_dist" "$KONAN_DATA_DIR" "$llvm"
    grep -Fx 'E1_CLI_SUITE_OK' "$OUTPUT_DIR/${st}-e1-suite.run.log"
    run "jvm-${st}-e1-diff" diff -u "$OUTPUT_DIR/oracle-e1.log" "$OUTPUT_DIR/${st}-e1-suite.run.log"

    runtime_gate "$st_work/two-stage-app.kexe" "${st}-two-stage-app" "$d1_rebuilt_dist"
    grep -Fx 'E1_STAGE_APP_OK' "$OUTPUT_DIR/${st}-two-stage-app.run.log"
    runtime_gate "$st_work/one-stage-app.kexe" "${st}-one-stage-app" "$d1_rebuilt_dist"
    grep -Fx 'E1_STAGE_APP_OK' "$OUTPUT_DIR/${st}-one-stage-app.run.log"
    run "jvm-${st}-two-stage-diff" diff -u "$OUTPUT_DIR/jvm-two-stage-app.run.log" "$OUTPUT_DIR/${st}-two-stage-app.run.log"
    run "jvm-${st}-one-stage-diff" diff -u "$OUTPUT_DIR/jvm-one-stage-app.run.log" "$OUTPUT_DIR/${st}-one-stage-app.run.log"
    rm -rf "$st_work"
done

run stage2-stage3-e1-suite-diff diff -u "$OUTPUT_DIR/stage2-e1-suite.run.log" "$OUTPUT_DIR/stage3-e1-suite.run.log"

# Run D1 7-section stdlib/runtime/cinterop/POSIX/zlib corpus on stage2 and stage3
plat_dir="$d1_rebuilt_dist/klib/platform/linux_x64"
for st in stage2 stage3; do
    st_d1="$TMPDIR/${st}-d1-work"
    rm -rf "$st_d1"
    mkdir -p "$st_d1/cinterop-tmp" "$st_d1/core-tmp" "$st_d1/app-bc"
    runtime_gate "$OUTPUT_DIR/$st/konanc-cli.kexe" "${st}-d1-cinterop" "$d1_rebuilt_dist" \
        cinterop \
        -target linux_x64 \
        -def "$WORK_ROOT/bootstrap/tests/D1/c/d1cbridge.def" \
        -o "$st_d1/d1cbridge.klib" \
        -no-default-libs \
        -Xccall-mode indirect \
        -compiler-option "-I$WORK_ROOT/bootstrap/tests/D1/c" \
        -libraryPath "$d1_c_dir" \
        -staticLibrary libd1_c_bridge.a \
        -l "$plat_dir/org.jetbrains.kotlin.native.platform.posix" \
        -Xtemporary-files-dir "$st_d1/cinterop-tmp" \
        -Xkonan-data-dir "$KONAN_DATA_DIR" \
        -Xoverride-konan-properties "airplaneMode=true;llvmHome.linux_x64=$llvm"
    rm -rf "$st_d1/cinterop-tmp" "$st_d1/d1cbridge.klib-build"
    test -s "$st_d1/d1cbridge.klib"

    runtime_gate "$OUTPUT_DIR/$st/konanc-cli.kexe" "${st}-d1-core-klib" "$d1_rebuilt_dist" \
        konanc \
        -kotlin-home "$d1_rebuilt_dist" \
        "-Xkonan-data-dir=$KONAN_DATA_DIR" \
        -target linux_x64 \
        -produce library \
        -no-default-libs \
        -Xmulti-platform \
        "-Xcommon-sources=$WORK_ROOT/bootstrap/tests/D1/D1CoreLib.kt" \
        -ea \
        -Xpartial-linkage-loglevel=error \
        "-Xoverride-konan-properties=airplaneMode=true;llvmHome.linux_x64=$llvm" \
        "-Xtemporary-files-dir=$st_d1/core-tmp" \
        -module-name org.jetbrains.kotlin.native.d1.core \
        -o "$st_d1/d1-core-lib.klib" \
        "$WORK_ROOT/bootstrap/tests/D1/D1CoreLib.kt"
    rm -rf "$st_d1/core-tmp"
    test -s "$st_d1/d1-core-lib.klib"

    runtime_gate "$OUTPUT_DIR/$st/konanc-cli.kexe" "${st}-d1-core-abi" "$d1_rebuilt_dist" \
        klib dump-abi "$st_d1/d1-core-lib.klib"
    cp -f "$OUTPUT_DIR/${st}-d1-core-abi.run.log" "$OUTPUT_DIR/${st}-d1-core-abi.txt"
    run "jvm-${st}-d1-abi-diff" diff -u "$OUTPUT_DIR/d1-core-abi.jvm.txt" "$OUTPUT_DIR/${st}-d1-core-abi.txt"

    : > "$st_d1/app-bc/d1-corpus-app.kexe.o"
    runtime_gate "$OUTPUT_DIR/$st/konanc-cli.kexe" "${st}-d1-corpus-bc" "$d1_rebuilt_dist" \
        konanc \
        -kotlin-home "$d1_rebuilt_dist" \
        "-Xkonan-data-dir=$KONAN_DATA_DIR" \
        -target linux_x64 \
        -produce program \
        -g \
        -no-default-libs \
        -ea \
        -Xpartial-linkage-loglevel=error \
        "-Xoverride-konan-properties=$link_override" \
        "-Xcache-directory=$sys_cache" \
        -library "$plat_dir/org.jetbrains.kotlin.native.platform.posix" \
        -library "$plat_dir/org.jetbrains.kotlin.native.platform.builtin" \
        -library "$plat_dir/org.jetbrains.kotlin.native.platform.zlib" \
        -library "$st_d1/d1cbridge.klib" \
        -library "$st_d1/d1-core-lib.klib" \
        -Xbinary=preCodegenInlineThreshold=0 \
        "-Xtemporary-files-dir=$st_d1/app-bc" \
        "-Xwrite-dependencies-to=$st_d1/d1-app-deps.txt" \
        -Xdisable-phases=CopyDefaultValuesToActual,BuildDFG,DCEPhase,PropertyAccessorInline,InlineClassPropertyAccessorsLowering,DevirtualizationAnalysis,RemoveRedundantCallsToStaticInitializersPhase,Devirtualization,UnboxInline,GHAPhase,EscapeAnalysis,CoroutinesLivenessAnalysis,StackProtectorPhase,MandatoryBitcodeLLVMPostprocessingPhase,ModuleBitcodeOptimization,LTOBitcodeOptimization,RemoveRedundantSafepoints,VerifyBitcode,ObjectFiles,Linker \
        -entry org.jetbrains.kotlin.native.d1.verify.main \
        -o "$st_d1/d1-corpus-app" \
        "$WORK_ROOT/bootstrap/tests/D1/D1Cases.kt" \
        "$WORK_ROOT/bootstrap/tests/D1/D1Native.kt"
    test -s "$st_d1/app-bc/out.bc"
    test -s "$st_d1/d1-app-deps.txt"

    run "${st}-d1-corpus-obj" env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
        "$llvm/bin/clang++" \
        -cc1 -emit-obj -disable-llvm-optzns -x ir -triple x86_64-unknown-linux-gnu -O0 -mrelocation-model pic \
        "$st_d1/app-bc/out.bc" -o "$st_d1/app-bc/d1-corpus-app.kexe.o"
    rm -f "$st_d1/app-bc/out.bc"
    test -s "$st_d1/app-bc/d1-corpus-app.kexe.o"

    "$llvm/bin/llvm-as" < /dev/null -o "$st_d1/app-bc/empty.bc"
    runtime_gate "$OUTPUT_DIR/$st/konanc-cli.kexe" "${st}-d1-corpus-link" "$d1_rebuilt_dist" \
        konanc \
        -kotlin-home "$d1_rebuilt_dist" \
        "-Xkonan-data-dir=$KONAN_DATA_DIR" \
        -target linux_x64 \
        -produce program \
        -g \
        -no-default-libs \
        -ea \
        -Xpartial-linkage-loglevel=error \
        "-Xoverride-konan-properties=$link_override" \
        "-Xcache-directory=$sys_cache" \
        -library "$plat_dir/org.jetbrains.kotlin.native.platform.posix" \
        -library "$plat_dir/org.jetbrains.kotlin.native.platform.builtin" \
        -library "$plat_dir/org.jetbrains.kotlin.native.platform.zlib" \
        -library "$st_d1/d1cbridge.klib" \
        -library "$st_d1/d1-core-lib.klib" \
        "-Xtemporary-files-dir=$st_d1/app-bc" \
        "-Xcompile-from-bitcode=$st_d1/app-bc/empty.bc" \
        "-Xread-dependencies-from=$st_d1/d1-app-deps.txt" \
        -Xdisable-phases=StackProtectorPhase,MandatoryBitcodeLLVMPostprocessingPhase,ModuleBitcodeOptimization,LTOBitcodeOptimization,RemoveRedundantSafepoints,WriteBitcodeFile,ObjectFiles \
        -o "$st_d1/d1-corpus-app"
    test -x "$st_d1/d1-corpus-app.kexe"

    runtime_gate "$st_d1/d1-corpus-app.kexe" "${st}-d1-corpus-exec" "$d1_rebuilt_dist" \
        "$d1_rebuilt_dist" "$OUTPUT_DIR/${st}-d1-core-abi.txt"
    run "jvm-${st}-d1-diff" diff -u "$OUTPUT_DIR/oracle-d1.log" "$OUTPUT_DIR/${st}-d1-corpus-exec.run.log"
    rm -rf "$st_d1"
done

run stage2-stage3-d1-corpus-diff diff -u "$OUTPUT_DIR/stage2-d1-corpus-exec.run.log" "$OUTPUT_DIR/stage3-d1-corpus-exec.run.log"

# Package final self-hosted Native distribution
pkg_dist="$OUTPUT_DIR/native-kotlin-dist-linux-x64"
mkdir -p \
    "$pkg_dist/bin" \
    "$pkg_dist/konan/targets/linux_x64/native" \
    "$pkg_dist/konan/platformDef/linux_x64" \
    "$pkg_dist/klib/common" \
    "$pkg_dist/klib/platform/linux_x64" \
    "$pkg_dist/klib/cache/linux_x64-gSTATIC-system"

ln -f "$OUTPUT_DIR/stage3/konanc-cli.kexe" "$pkg_dist/bin/konanc-cli.kexe" 2>/dev/null || \
    cp -f "$OUTPUT_DIR/stage3/konanc-cli.kexe" "$pkg_dist/bin/konanc-cli.kexe"
chmod +x "$pkg_dist/bin/konanc-cli.kexe"

for tool in konanc cinterop klib; do
    cat > "$pkg_dist/bin/$tool" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail
DIST_DIR=\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)
export KONAN_HOME="\${KONAN_HOME:-\$DIST_DIR}"
export KOTLIN_NATIVE_HOME="\${KOTLIN_NATIVE_HOME:-\$DIST_DIR}"
exec "\$DIST_DIR/bin/konanc-cli.kexe" $tool "\$@"
EOF
    chmod +x "$pkg_dist/bin/$tool"
done

cp -f "$d1_rebuilt_dist/konan/konan.properties" "$pkg_dist/konan/konan.properties"
sha256sum "$pkg_dist/bin/konanc-cli.kexe" | awk '{print $1}' > "$pkg_dist/konan/compiler.fingerprint"
cp -f "$d1_rebuilt_dist/konan/targets/linux_x64/runtime.fingerprint" "$pkg_dist/konan/targets/linux_x64/runtime.fingerprint"
cp -f "$d1_rebuilt_dist/konan/targets/linux_x64/native/"* "$pkg_dist/konan/targets/linux_x64/native/"
cp -f "$d1_rebuilt_dist/konan/platformDef/linux_x64/"*.def "$pkg_dist/konan/platformDef/linux_x64/"
cp -a "$d1_rebuilt_dist/klib/common/stdlib" "$pkg_dist/klib/common/stdlib"
cp -a "$d1_rebuilt_dist/klib/platform/linux_x64/." "$pkg_dist/klib/platform/linux_x64/"
cp -a "$d1_rebuilt_dist/klib/cache/linux_x64-gSTATIC-system/stdlib-cache" "$pkg_dist/klib/cache/linux_x64-gSTATIC-system/"

cp -f "$upstream/license/LICENSE.txt" "$pkg_dist/LICENSE.txt"
cat > "$pkg_dist/VERSION.txt" <<EOF
Kotlin/Native Self-Hosted Native Distribution (linux_x64)
Upstream Commit: 9180ce6a7a7d3627f88b424717583f28ddca052d
Kotlin Version: 2.4.20-dev-7885
Host & Target: linux_x64 (x86_64-unknown-linux-gnu)
Bootstrap Stage: stage3 (built by stage2 under env -i PATH=/nonexistent, 0 JVM)
EOF

cat > "$pkg_dist/SUPPORT_MATRIX.md" <<'EOF'
# Native-Hosted Kotlin/Native Toolchain Support Matrix

## Supported Host & Target
- **Host OS / Arch**: `linux_x64` (`x86_64-unknown-linux-gnu`, glibc >= 2.19)
- **Target OS / Arch**: `linux_x64` (`x86_64-unknown-linux-gnu`)
- **Upstream Baseline**: `9180ce6a7a7d3627f88b424717583f28ddca052d` (`2.4.20-dev-7885`)

## Supported Entrypoints & Modes
- `konanc`:
  - `-produce library` (`.kt` -> `.klib`, including multi-fragment HMPP `-Xfragments` / `-Xfragment-refines` / `-Xfragment-sources`)
  - `-produce program` (two-stage `.klib` -> `.kexe` and one-stage `.kt` -> `.kexe`)
  - `-produce static_cache` (`-Xadd-cache` / `-Xcache-directory`)
  - `-produce bitcode` / `-Xcompile-from-bitcode` / `-Xread-dependencies-from` / `-Xwrite-dependencies-to`
- `cinterop`:
  - Native-hosted C interoperability indexer (`libclang`) and stub generator (`.def` + `.h` -> interop `.klib` with C bridges)
- `klib`:
  - `info`, `contents`, `signatures`, `dump-abi`, `dump-ir`, `dump-ir-signatures`, `dump-metadata`, `dump-metadata-signatures`
- `defFileDependencies` & `generatePlatformLibraries`:
  - Native-hosted `.def` dependency graph resolution and platform library generation (`posix`, `builtin`, `iconv`, `linux`, `zlib`)

## External Native/C/C++ Dependencies (Explicitly Declared, Zero JVM)
- `llvm-21-x86_64-linux-dev-116` (`clang`, `clang++`, `llvm-ar`, `llvm-as`, `llvm-link`, `llvm-bcanalyzer`, `ld.lld`, `libclang.so.21.1`)
- `x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2` (target sysroot & CRT objects)
- Host system shared libraries: `libc.so.6`, `libstdc++.so.6`, `libgmp.so.10`, `libicuuc.so.78`, `libicui18n.so.78`, `libicudata.so.78`, `libz.so.1`
EOF

cat > "$pkg_dist/KNOWN_LIMITATIONS.md" <<'EOF'
# Known Limitations & Scope Boundaries

- **Host/Target Scope**: Validated and packaged for `linux_x64` host and target only. Apple (`macos_*`, `ios_*`), Windows (`mingw_x64`), and Android targets are excluded in this milestone.
- **Compiler Plugins & Gradle**: Arbitrary JVM-hosted third-party compiler plugins (`ServiceLoader` JARs) and the Gradle build ecosystem are out of scope for the standalone Native CLI.
- **Scripting & REPL**: Kotlin Scripting (`-script`) and interactive REPL (`-Xrepl`) are disabled in `NativeKlibCliPipeline`.
- **Low-Memory Resource Profile**: Under a strict `1536 MiB` cgroup `MemoryHigh` ceiling (`1` CPU), large monolithic binaries and `stdlib-cache` use the 3-step bitcode emission (`-Xdisable-phases=...ObjectFiles,Linker`) + `clang++ -cc1 -emit-obj -O0` + linker invocation recipe.
EOF

cat > "$pkg_dist/REPRODUCIBLE_BUILD.md" <<'EOF'
# Reproducible Self-Hosted Build & Verification

1. **Verify Baseline & Closure Fingerprints**:
   - `sha256sum --quiet -c bootstrap/manifests/production.sha256` (`3,430` production files)
   - `(cd "$DEPS_ROOT" && sha256sum --quiet -c "$WORK_ROOT/bootstrap/manifests/dependencies.sha256")`
2. **Rebuild Runtime Bitcode, `stdlib`, `stdlib-cache`, and Platform Libraries (`D1`)**:
   - Execute `porting/D1/build.sh all` under `scripts/with-zram-build-limits.sh` (`env -i PATH=/nonexistent`, 0 JVM).
3. **Execute `stage1 -> stage2 -> stage3` Bootstrap & Full Regression (`S1`)**:
   - Execute `bootstrap/manifests/build.sh all` under `scripts/with-zram-build-limits.sh`.
EOF

cp -f bootstrap/manifests/production.sha256 "$pkg_dist/SOURCE_CLOSURE.tsv"
(cd "$pkg_dist" && find . -type f ! -name manifest.sha256 -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > manifest.sha256)
cp -f "$pkg_dist/manifest.sha256" "$OUTPUT_DIR/dist-manifest.sha256"
cp -f "$pkg_dist/VERSION.txt" "$OUTPUT_DIR/dist-VERSION.txt"
cp -f "$pkg_dist/SUPPORT_MATRIX.md" "$OUTPUT_DIR/dist-SUPPORT_MATRIX.md"
cp -f "$pkg_dist/KNOWN_LIMITATIONS.md" "$OUTPUT_DIR/dist-KNOWN_LIMITATIONS.md"
cp -f "$pkg_dist/REPRODUCIBLE_BUILD.md" "$OUTPUT_DIR/dist-REPRODUCIBLE_BUILD.md"

# End-to-end verification of the packaged distribution under env -i PATH=/nonexistent
pkg_smoke="$TMPDIR/pkg-smoke"
mkdir -p "$pkg_smoke/tmp"
: > "$pkg_smoke/tmp/pkg-app.kexe.o"
runtime_gate "$pkg_dist/bin/konanc-cli.kexe" pkg-dist-smoke-bc "$pkg_dist" \
    konanc \
    -kotlin-home "$pkg_dist" \
    "-Xkonan-data-dir=$KONAN_DATA_DIR" \
    -target linux_x64 \
    -produce program \
    -no-default-libs \
    -g \
    "-Xcache-directory=$pkg_dist/klib/cache/linux_x64-gSTATIC-system" \
    -ea \
    -Xpartial-linkage-loglevel=error \
    "-Xoverride-konan-properties=$link_override" \
    -Xbinary=preCodegenInlineThreshold=0 \
    "-Xtemporary-files-dir=$pkg_smoke/tmp" \
    "-Xwrite-dependencies-to=$pkg_smoke/deps.txt" \
    -Xdisable-phases=CopyDefaultValuesToActual,BuildDFG,DCEPhase,PropertyAccessorInline,InlineClassPropertyAccessorsLowering,DevirtualizationAnalysis,RemoveRedundantCallsToStaticInitializersPhase,Devirtualization,UnboxInline,GHAPhase,EscapeAnalysis,CoroutinesLivenessAnalysis,StackProtectorPhase,MandatoryBitcodeLLVMPostprocessingPhase,ModuleBitcodeOptimization,LTOBitcodeOptimization,RemoveRedundantSafepoints,VerifyBitcode,ObjectFiles,Linker \
    -entry e1.fixture.app.main \
    -o "$pkg_smoke/pkg-app" \
    "$fixtures_dir/BaseLibA.kt" \
    "$fixtures_dir/BaseLibB.kt" \
    "$fixtures_dir/AppMain.kt"
run pkg-dist-smoke-obj env -i PATH=/nonexistent LC_ALL=C TZ=UTC TMPDIR="$TMPDIR" \
    "$llvm/bin/clang++" \
    -cc1 -emit-obj -disable-llvm-optzns -x ir -triple x86_64-unknown-linux-gnu -O0 -mrelocation-model pic \
    "$pkg_smoke/tmp/out.bc" -o "$pkg_smoke/tmp/pkg-app.kexe.o"
rm -f "$pkg_smoke/tmp/out.bc"
"$llvm/bin/llvm-as" < /dev/null -o "$pkg_smoke/tmp/empty.bc"
runtime_gate "$pkg_dist/bin/konanc-cli.kexe" pkg-dist-smoke-link "$pkg_dist" \
    konanc \
    -kotlin-home "$pkg_dist" \
    "-Xkonan-data-dir=$KONAN_DATA_DIR" \
    -target linux_x64 \
    -produce program \
    -no-default-libs \
    -g \
    "-Xcache-directory=$pkg_dist/klib/cache/linux_x64-gSTATIC-system" \
    -ea \
    -Xpartial-linkage-loglevel=error \
    "-Xoverride-konan-properties=$link_override" \
    "-Xtemporary-files-dir=$pkg_smoke/tmp" \
    "-Xcompile-from-bitcode=$pkg_smoke/tmp/empty.bc" \
    "-Xread-dependencies-from=$pkg_smoke/deps.txt" \
    -Xdisable-phases=StackProtectorPhase,MandatoryBitcodeLLVMPostprocessingPhase,ModuleBitcodeOptimization,LTOBitcodeOptimization,RemoveRedundantSafepoints,WriteBitcodeFile,ObjectFiles \
    -o "$pkg_smoke/pkg-app"
runtime_gate "$pkg_smoke/pkg-app.kexe" pkg-dist-smoke-run "$pkg_dist"
grep -Fx 'E1_STAGE_APP_OK' "$OUTPUT_DIR/pkg-dist-smoke-run.run.log"
run pkg-dist-smoke-diff diff -u "$OUTPUT_DIR/jvm-one-stage-app.run.log" "$OUTPUT_DIR/pkg-dist-smoke-run.run.log"
rm -rf "$pkg_smoke"

cp -f "$OUTPUT_DIR/oracle-e1.log" "$OUTPUT_DIR/oracle.log"
cp -f "$OUTPUT_DIR/jvm-stage3-e1-diff.log" "$OUTPUT_DIR/jvm-native-diff.log"

rm -rf "$DEPS_ROOT/build/s1/native-kotlin-dist-linux-x64"
mkdir -p "$DEPS_ROOT/build/s1"
cp -a "$pkg_dist" "$DEPS_ROOT/build/s1/native-kotlin-dist-linux-x64"

# Keep stage2/stage3 reports and packaged distribution manifest; remove bulky duplicate binaries before hashing artifacts
rm -rf "$OUTPUT_DIR/stage2" "$OUTPUT_DIR/stage3" "$pkg_dist"
find "$OUTPUT_DIR" -type f ! -path '*/konan-home/*' ! -path '*/tmp/*' ! -name artifacts.sha256 \
    -print0 | sort -z | xargs -0 sha256sum > "$OUTPUT_DIR/artifacts.sha256"

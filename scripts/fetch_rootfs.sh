#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# T83: fetch_rootfs.sh —— 内置 Ubuntu rootfs 构建期暂存（应用内安装 → 内置交付）
#
# 产品转向：运行时从镜像源下载安装的体验与"内置开箱即用"差距巨大，转为
# APK 内置交付（学习 Operit 的内置思路 + 自有创新：构建期固定指纹的可审计链）。
# 本脚本是该链的构建期一半：
#
#   自仓托管 Release（tag ubuntu-rootfs-24.04.4-full；rootfs.yml CI 用
#     scripts/build_full_rootfs.sh 从官方 ubuntu-base 扩建的完整环境，
#     真值表 rootfs-digests.txt 附于同一 Release —— URL + SHA-256 双固定于下表）
#     → 下载 → 校验（脚本固定值 + rootfs-bundle.sha256 清单 双重比对）
#     → 暂存为 platform/terminal/src/main/jniLibs/<abi>/libubuntu-rootfs.so
#     → （gradle assemble 时打进 APK；legacy packaging 使安装器解出到
#        nativeLibraryDir —— 与 libproot.so 同机制）
#
# 运行时一半见 BundledRootfsSource.kt（resolve 长度校验 + 本地拷贝 SHA-256 复验）。
#
# 用法：
#   scripts/fetch_rootfs.sh                     # 拉取全部 3 ABI + 双重校验 + 暂存（幂等）
#   scripts/fetch_rootfs.sh --arch arm64-v8a    # 只拉指定 ABI（PR CI 快路径：省 2/3 流量）
#   scripts/fetch_rootfs.sh --check             # 只校验已暂存档案（不下载）
#   scripts/fetch_rootfs.sh --clean             # 删除暂存档案
#
# 退出码：0 成功；1 校验失败/缺依赖（绝不带病暂存 —— 宁可构建失败，不可静默
# 打包一个指纹不符的 rootfs）。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

log()  { echo "[fetch_rootfs] $*"; }
fail() { echo "[fetch_rootfs] ❌ $*" >&2; exit 1; }

MODULE_DIR="$(cd "$(dirname "$0")/../platform/terminal" && pwd)"
MANIFEST="$MODULE_DIR/rootfs-bundle.sha256"
STAGE_BASE="$MODULE_DIR/src/main/jniLibs"
POINT_VERSION="24.04.4"
# T84 完整 rootfs 托管源（本仓 Release；builder 见 rootfs.yml / build_full_rootfs.sh）
BASE_URL="https://github.com/AceGuru-mjh/Android-Guru-Agent/releases/download/ubuntu-rootfs-24.04.4-full"

# T84：--arch <abi> 只处理该 ABI（PR CI 的 build-apk job 只验证 arm64 打包链，
# 拉全 3 份是 tag 发布（universal）才需要的）。缺省 = 全部。
WANTED_ABIS=("arm64-v8a" "x86_64" "armeabi-v7a")
if [ "${1:-}" = "--arch" ]; then
  [ -n "${2:-}" ] || fail "--arch requires an abi argument (arm64-v8a|x86_64|armeabi-v7a)"
  case "$2" in
    arm64-v8a|x86_64|armeabi-v7a) WANTED_ABIS=("$2") ;;
    *) fail "unknown abi: $2" ;;
  esac
  shift 2
fi

# T84 完整 rootfs 指纹（rootfs.yml CI 构建，run #10，2026-09-19 实测）—— 与
# rootfs-bundle.sha256、BundledRootfsSource.kt 注册表三处一致（BundledRootfsSourceTest
# 交叉校验防漂移）。完整环境：58 包（gcc/python3-dev/nodejs/npm/cmake/gdb/ripgrep/…），
# 压缩 ~282-310MB/架构，解压 ~0.93-1.1GB（unpacked 字段见托管 Release rootfs-digests.txt）。
# 架构元组：<android abi> <ubuntu arch> <sha256> <size>
ARTIFACTS=(
  "arm64-v8a|arm64|3b8a82393304e38a5209ad1f2b32e6160506ecfc06dda33f3773b9e6b2e392e2|314655061"
  "x86_64|amd64|57fb03f916cae40202134594a6ad063167174714e1ad36a50f0575b015b87228|324010830"
  "armeabi-v7a|armhf|fe4e1a0ccd8d73c376c8ed7281a0ccc60041dfba71d735b163a2e657558e250a|294939555"
)

# 只保留 --arch 选中的条目（内部过滤，不改 ARTIFACTS 真值表）
FILTERED_ARTIFACTS=()
for entry in "${ARTIFACTS[@]}"; do
  IFS='|' read -r abi _ua _sha _sz <<< "$entry"
  for want in "${WANTED_ABIS[@]}"; do
    [ "$abi" = "$want" ] && FILTERED_ARTIFACTS+=("$entry")
  done
done
[ ${#FILTERED_ARTIFACTS[@]} -gt 0 ] || fail "no artifacts selected for: ${WANTED_ABIS[*]}"

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
[ -f "$MANIFEST" ] || fail "manifest missing: $MANIFEST"

# 清单交叉校验：脚本固定指纹必须与 rootfs-bundle.sha256 一致（防三处漂移之二）。
verify_manifest_agreement() {
  # 三处真值表互验用全量 ARTIFACTS（不受 --arch 过滤影响 —— 防漂移检查必须全量）
  for entry in "${ARTIFACTS[@]}"; do
    IFS='|' read -r abi _ubuntu_arch sha _size <<< "$entry"
    expected_line="$sha  src/main/jniLibs/$abi/libubuntu-rootfs.so"
    grep -qF "$expected_line" "$MANIFEST" \
      || fail "manifest disagreement for $abi — script table and rootfs-bundle.sha256 drifted"
  done
  log "manifest agreement OK (script table == rootfs-bundle.sha256)"
}

verify_staged() {
  # 日志走 stderr —— stdout 只回传已验证计数（供命令替换捕获）。只验选中 ABI。
  local staged=0
  for entry in "${FILTERED_ARTIFACTS[@]}"; do
    IFS='|' read -r abi _ubuntu_arch sha size <<< "$entry"
    local f="$STAGE_BASE/$abi/libubuntu-rootfs.so"
    if [ -f "$f" ]; then
      local actual
      actual="$(sha256sum "$f" | cut -d' ' -f1)"
      [ "$actual" = "$sha" ] || fail "staged $abi archive digest mismatch (expected=$sha actual=$actual)"
      local actual_size
      actual_size="$(stat -c%s "$f")"
      [ "$actual_size" = "$size" ] || fail "staged $abi archive size mismatch (expected=$size actual=$actual_size)"
      log "staged $abi OK ($(numfmt --to=iec "$size" 2>/dev/null || echo "${size}B"))" >&2
      staged=$((staged + 1))
    fi
  done
  echo "$staged"
}

case "${1:-fetch}" in
  --clean)
    for entry in "${FILTERED_ARTIFACTS[@]}"; do
      IFS='|' read -r abi _ua _sha _sz <<< "$entry"
      rm -f "$STAGE_BASE/$abi/libubuntu-rootfs.so"
    done
    log "staged archives removed (selected: ${WANTED_ABIS[*]})"
    exit 0
    ;;
  --check)
    verify_manifest_agreement
    staged="$(verify_staged)"
    if [ "$staged" -eq ${#FILTERED_ARTIFACTS[@]} ]; then
      log "✅ all ${#FILTERED_ARTIFACTS[@]} selected archives verified (${WANTED_ABIS[*]})"
    else
      log "⚠️ only $staged/${#FILTERED_ARTIFACTS[@]} selected staged (run without --check to fetch)"
      exit 1
    fi
    exit 0
    ;;
  fetch) : ;;
  *) fail "unknown argument: $1 (use --arch <abi> / --check / --clean / none)" ;;
esac

verify_manifest_agreement

# 幂等：已暂存且指纹正确 → 跳过下载（本地反复 assemble 不重复花流量）。
already="$(verify_staged || true)"
if [ "$already" -eq ${#FILTERED_ARTIFACTS[@]} ]; then
  log "✅ all ${#FILTERED_ARTIFACTS[@]} selected archives already staged & verified — nothing to do"
  exit 0
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

for entry in "${FILTERED_ARTIFACTS[@]}"; do
  IFS='|' read -r abi ubuntu_arch sha size <<< "$entry"
  target="$STAGE_BASE/$abi/libubuntu-rootfs.so"
  if [ -f "$target" ]; then
    log "$abi already staged — skip"
    continue
  fi
  url="$BASE_URL/apex-ubuntu-full-$POINT_VERSION-$ubuntu_arch.tar.gz"
  tmp_archive="$TMP_DIR/apex-ubuntu-full-$POINT_VERSION-$ubuntu_arch.tar.gz"
  log "downloading $abi (~$(numfmt --to=iec "$size" 2>/dev/null || echo "${size}B")) from $url"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 30 -o "$tmp_archive" "$url" \
    || fail "download failed for $ubuntu_arch"
  # 双重校验之一：脚本固定指纹
  actual="$(sha256sum "$tmp_archive" | cut -d' ' -f1)"
  [ "$actual" = "$sha" ] || fail "digest mismatch for $ubuntu_arch (expected=$sha actual=$actual) — refusing to stage"
  actual_size="$(stat -c%s "$tmp_archive")"
  [ "$actual_size" = "$size" ] || fail "size mismatch for $ubuntu_arch (expected=$size actual=$actual_size)"
  mkdir -p "$STAGE_BASE/$abi"
  mv "$tmp_archive" "$target"
  log "staged $abi → src/main/jniLibs/$abi/libubuntu-rootfs.so"
done

# 双重校验之二：暂存结果整体过一遍 manifest（sha256sum -c，与 CI proot 校验同格式）
staged="$(verify_staged)"
if [ "$staged" -ne ${#FILTERED_ARTIFACTS[@]} ]; then
  fail "staging incomplete ($staged/${#FILTERED_ARTIFACTS[@]})"
fi
log "✅ all ${#FILTERED_ARTIFACTS[@]} bundled rootfs archives staged & verified (selected: ${WANTED_ABIS[*]})"

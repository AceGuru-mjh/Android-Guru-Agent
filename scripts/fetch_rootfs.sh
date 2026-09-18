#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# T83: fetch_rootfs.sh —— 内置 Ubuntu rootfs 构建期暂存（应用内安装 → 内置交付）
#
# 产品转向：运行时从镜像源下载安装的体验与"内置开箱即用"差距巨大，转为
# APK 内置交付（学习 Operit 的内置思路 + 自有创新：构建期固定指纹的可审计链）。
# 本脚本是该链的构建期一半：
#
#   官方 cdimage（URL + SHA-256 双固定于下表）
#     → 下载 → 校验（脚本固定值 + rootfs-bundle.sha256 清单 双重比对）
#     → 暂存为 platform/terminal/src/main/jniLibs/<abi>/libubuntu-rootfs.so
#     → （gradle assemble 时打进 APK；legacy packaging 使安装器解出到
#        nativeLibraryDir —— 与 libproot.so 同机制）
#
# 运行时一半见 BundledRootfsSource.kt（resolve 长度校验 + 本地拷贝 SHA-256 复验）。
#
# 用法：
#   scripts/fetch_rootfs.sh            # 拉取全部 3 ABI + 双重校验 + 暂存（幂等：已就绪跳过）
#   scripts/fetch_rootfs.sh --check    # 只校验已暂存档案（不下载；CI 快速路径）
#   scripts/fetch_rootfs.sh --clean    # 删除暂存档案
#
# 退出码：0 成功；1 校验失败/缺依赖（绝不带病暂存 —— 宁可构建失败，不可静默
# 打包一个指纹不符的 rootfs）。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "$0")/../platform/terminal" && pwd)"
MANIFEST="$MODULE_DIR/rootfs-bundle.sha256"
STAGE_BASE="$MODULE_DIR/src/main/jniLibs"
POINT_VERSION="24.04.4"
BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"

# 官方 SHA256SUMS（2026-02 实测）逐字节真值 —— 与 rootfs-bundle.sha256、
# BundledRootfsSource.kt 注册表三处一致（BundledRootfsSourceTest 交叉校验）。
# 架构元组：<android abi> <ubuntu arch> <sha256> <size>
ARTIFACTS=(
  "arm64-v8a|arm64|04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2|29870567"
  "x86_64|amd64|c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58|29989394"
  "armeabi-v7a|armhf|991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4|27088043"
)

log()  { echo "[fetch_rootfs] $*"; }
fail() { echo "[fetch_rootfs] ❌ $*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"
[ -f "$MANIFEST" ] || fail "manifest missing: $MANIFEST"

# 清单交叉校验：脚本固定指纹必须与 rootfs-bundle.sha256 一致（防三处漂移之二）。
verify_manifest_agreement() {
  for entry in "${ARTIFACTS[@]}"; do
    IFS='|' read -r abi _ubuntu_arch sha _size <<< "$entry"
    expected_line="$sha  src/main/jniLibs/$abi/libubuntu-rootfs.so"
    grep -qF "$expected_line" "$MANIFEST" \
      || fail "manifest disagreement for $abi — script table and rootfs-bundle.sha256 drifted"
  done
  log "manifest agreement OK (script table == rootfs-bundle.sha256)"
}

verify_staged() {
  # 日志走 stderr —— stdout 只回传已验证计数（供命令替换捕获）。
  local staged=0
  for entry in "${ARTIFACTS[@]}"; do
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
    for entry in "${ARTIFACTS[@]}"; do
      IFS='|' read -r abi _ua _sha _sz <<< "$entry"
      rm -f "$STAGE_BASE/$abi/libubuntu-rootfs.so"
    done
    log "staged archives removed"
    exit 0
    ;;
  --check)
    verify_manifest_agreement
    staged="$(verify_staged)"
    if [ "$staged" -eq ${#ARTIFACTS[@]} ]; then
      log "✅ all ${#ARTIFACTS[@]} staged archives verified"
    else
      log "⚠️ only $staged/${#ARTIFACTS[@]} staged (run without --check to fetch)"
      exit 1
    fi
    exit 0
    ;;
  fetch) : ;;
  *) fail "unknown argument: $1 (use --check / --clean / none)" ;;
esac

verify_manifest_agreement

# 幂等：已暂存且指纹正确 → 跳过下载（本地反复 assemble 不重复花流量）。
already="$(verify_staged || true)"
if [ "$already" -eq ${#ARTIFACTS[@]} ]; then
  log "✅ all archives already staged & verified — nothing to do"
  exit 0
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

for entry in "${ARTIFACTS[@]}"; do
  IFS='|' read -r abi ubuntu_arch sha size <<< "$entry"
  target="$STAGE_BASE/$abi/libubuntu-rootfs.so"
  if [ -f "$target" ]; then
    log "$abi already staged — skip"
    continue
  fi
  url="$BASE_URL/ubuntu-base-$POINT_VERSION-base-$ubuntu_arch.tar.gz"
  tmp_archive="$TMP_DIR/ubuntu-base-$POINT_VERSION-base-$ubuntu_arch.tar.gz"
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
if [ "$staged" -ne ${#ARTIFACTS[@]} ]; then
  fail "staging incomplete ($staged/${#ARTIFACTS[@]})"
fi
log "✅ all ${#ARTIFACTS[@]} bundled rootfs archives staged & verified (total ~87MB → jniLibs)"
log "   universal APK ≈ +87MB；arm64 纯净 APK ≈ +29MB（ABI 过滤自动生效）"

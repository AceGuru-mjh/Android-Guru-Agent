#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# build_full_rootfs.sh —— 「完整 Ubuntu rootfs」的可复现构建器（T84）。
#
# 背景（2026-09 产品修正）：v1.2.0 内置的是官方 ubuntu-base 最小骨架
# （arm64 仅 ~29MB）——「Ubuntu 大小接近 300MB，这是偷工减料」。本脚本把
# ubuntu-base 扩建成真正的完整 CLI 工作环境（~300MB 压缩档，gcc/python3/
# git/cmake/vim/… 开箱即用），产物交给 fetch_rootfs.sh 暂存进 APK。
#
# 可审计链（自有创新，Operit 无此设计）：
#   官方 ubuntu-base（URL + SHA-256 双固定，与 fetch_rootfs.sh 同表）
#     → 本脚本（提交进仓库：构建代码可审）+ scripts/rootfs-packages.txt（清单可审）
#     → 双执行器：root 可用（CI runner）→ 真实 chroot + binfmt qemu-user-static
#       （Ubuntu/Debian 官方跨架构 bootstrap 同路径）；无 root（受限沙盒）→
#       proot（路径翻译 + 伪 root id）+ qemu-user 静态二进制兜底
#     → 产物 tar.gz（digest 由 fetch_rootfs.sh / Kotlin 注册表 / 清单三处钉死）
#
# 执行器决策依据（2026-09 CI 实测）：proot+qemu-user 10 在 armhf（32 位）下 dpkg
# 解包 libc6 时触发堆翻译 bug「double free or corruption (out)」（arm64/amd64
# 同代码路径存活）；真实 chroot + binfmt 无此问题 —— dpkg 在真实 root 下语义完整。
#
# 分阶段幂等（长构建可断点续跑 —— qemu 模拟下 arm 架构安装耗时数十分钟）：
#   prepare  下载校验 ubuntu-base → 解包 → 注入构建源/policy-rc.d（marker 跳过）
#   update   apt-get update（可重跑）
#   install  apt-get install 全清单（可重跑：已装跳过，中断后 dpkg --configure -a 自愈）
#   locale   预生成 en_US.UTF-8 + zh_CN.UTF-8
#   clean    净化（apt 缓存/日志/机器标识/qemu 注入物）+ 回写官方 apt 源
#   package  tar.gz 打包 + 指纹报告（钉进三处真值表）
#   all      顺序执行全部（默认 —— CI/一次性环境用）
#
# 用法：
#   scripts/build_full_rootfs.sh arm64                # 全流程
#   scripts/build_full_rootfs.sh arm64 install        # 单阶段（断点续跑）
#
# 环境变量：
#   APEX_TOOLS_DIR    构建工具目录（默认 ~/.apex-rootfs-tools）
#   APEX_WORK_DIR     工作目录（默认 /tmp/apex-rootfs-build；每架构峰值 ~3GB）
#   APEX_BUILD_MIRROR 构建期 apt 镜像（默认清华 TUNA，http —— qemu 下 gnutls TLS
#                     握手不可靠，包完整性由 Ubuntu 签名 Release/Packages 哈希链
#                     保证，与镜像与协议无关）
# 退出码：0 成功；1 失败（绝不带病产出 —— 与 fetch_rootfs.sh 同哲学）。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ARCH="${1:-}"
STAGE="${2:-all}"

log()  { echo "[build_rootfs] $*"; }
fail() { echo "[build_rootfs] ❌ $*" >&2; exit 1; }

# ── 官方 ubuntu-base 固定表（与 scripts/fetch_rootfs.sh / rootfs-bundle.sha256
#    的 BASE 指纹同源；升级 point release = 三处同步显式变更）──
POINT_VERSION="24.04.4"
BASE_SHA256_ARM64="04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
BASE_SHA256_AMD64="c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58"
BASE_SHA256_ARMHF="991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4"
BASE_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"
BASE_OFFICIAL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release"

# ── 构建工具固定表（Debian trixie pool 实测下载，2026-09；qemu 为静态链接）──
# 工具完整性说明：proot/qemu 只做路径/指令翻译，包内容完整性由 guest 内 apt 的
# Ubuntu 签名 Release/Packages 哈希链保证 —— 构建工具本身无签名校验是可接受的。
DEB_POOL="http://deb.debian.org/debian/pool/main"
TOOLS_URL_PROOT="$DEB_POOL/p/proot/proot_5.4.0-3_amd64.deb"
TOOLS_URL_TALLOC="$DEB_POOL/t/talloc/libtalloc2_2.4.0-f2_amd64.deb"
TOOLS_URL_QEMU="$DEB_POOL/q/qemu/qemu-user_10.0.13+ds-0+deb13u1_amd64.deb"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGES_FILE="$REPO_DIR/scripts/rootfs-packages.txt"
TOOLS_DIR="${APEX_TOOLS_DIR:-$HOME/.apex-rootfs-tools}"
WORK_DIR="${APEX_WORK_DIR:-/tmp/apex-rootfs-build}"
MIRROR="${APEX_BUILD_MIRROR:-http://mirrors.tuna.tsinghua.edu.cn}"

case "$ARCH" in
  arm64) UBUNTU_ARCH="arm64"; QEMU_BIN="qemu-aarch64" ;;
  amd64) UBUNTU_ARCH="amd64"; QEMU_BIN="" ;;
  armhf) UBUNTU_ARCH="armhf"; QEMU_BIN="qemu-arm" ;;
  *) fail "usage: $0 <arm64|amd64|armhf> [all|prepare|update|install|locale|clean|package]" ;;
esac
case "$STAGE" in
  all|prepare|update|install|locale|clean|package) ;;
  *) fail "unknown stage: $STAGE" ;;
esac

[ -f "$PACKAGES_FILE" ] || fail "packages manifest missing: $PACKAGES_FILE"
command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum is required"

# ── 执行器选择：root 可用（CI runner）→ 真实 chroot；无 root → proot 兑底 ──
# SUDO_CMD 用数组：root 直跑时为空数组（"${SUDO_CMD[@]}" 展开为零词 ——
# 字符串形式 "$SUDO" cmd 在空时会变成空命令名「: command not found」，实测踩坑）
SUDO_CMD=()
GUEST_MODE="proot"
if [ "$(id -u)" = "0" ]; then
  GUEST_MODE="chroot"
elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  GUEST_MODE="chroot"; SUDO_CMD=(sudo)
fi

# ─────────────────────────────────────────────────────────────────────────────
# 工具自举（双路径）
#   chroot 模式：宿主 apt 装 qemu-user-static（binfmt 注册）—— dpkg 走真实 root
#   proot 模式：无 root 环境从 Debian pool 拉 deb 并就地解包（幂等）
# ─────────────────────────────────────────────────────────────────────────────
setup_tools() {
  if [ "$GUEST_MODE" = "chroot" ]; then
    [ -z "$QEMU_BIN" ] && { log "✅ native arch — chroot mode, no emulator needed"; return 0; }
    if [ -f /proc/sys/fs/binfmt_misc/qemu-arm ] && [ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
      log "✅ qemu binfmt handlers already registered"
      return 0
    fi
    log "installing qemu-user-static on host (binfmt registration)"
    # binfmt_misc 可用性保障（GitHub runner 已挂；幂等补挂）
    [ -d /proc/sys/fs/binfmt_misc ] || "${SUDO_CMD[@]}" modprobe binfmt_misc 2>/dev/null || true
    "${SUDO_CMD[@]}" mountpoint -q /proc/sys/fs/binfmt_misc \
      || "${SUDO_CMD[@]}" mount -t binfmt_misc none /proc/sys/fs/binfmt_misc 2>/dev/null || true
    "${SUDO_CMD[@]}" apt-get update -qq || fail "host apt-get update failed"
    # binfmt-support 提供 update-binfmts 命令（qemu-user-static 的依赖；显式钉名防包装差异）
    "${SUDO_CMD[@]}" apt-get install -y -qq qemu-user-static binfmt-support >/dev/null \
      || fail "qemu-user-static install failed"
    # binfmt 注册（包 postinst 通常已做；幂等补注册防 systemd-binfmt 未激活）
    local a
    for a in qemu-arm qemu-aarch64; do
      if [ ! -f "/proc/sys/fs/binfmt_misc/$a" ]; then
        "${SUDO_CMD[@]}" update-binfmts --enable "$a" || fail "binfmt enable failed: $a"
      fi
    done
    [ -f /proc/sys/fs/binfmt_misc/qemu-arm ]     || fail "binfmt qemu-arm missing"
    [ -f /proc/sys/fs/binfmt_misc/qemu-aarch64 ] || fail "binfmt qemu-aarch64 missing"
    return 0
  fi
  command -v dpkg-deb >/dev/null 2>&1 || fail "dpkg-deb required (proot mode)"
  mkdir -p "$TOOLS_DIR/root"
  if [ -x "$TOOLS_DIR/root/usr/bin/proot" ] && [ -x "$TOOLS_DIR/root/usr/bin/qemu-aarch64" ] \
     && [ -x "$TOOLS_DIR/root/usr/lib/x86_64-linux-gnu/libtalloc.so.2" ]; then
    return 0
  fi
  for url in "$TOOLS_URL_PROOT" "$TOOLS_URL_TALLOC" "$TOOLS_URL_QEMU"; do
    local f="$TOOLS_DIR/$(basename "$url")"
    if [ ! -s "$f" ]; then
      log "downloading tool deb: $(basename "$url")"
      curl -fL --retry 3 --connect-timeout 30 -o "$f" "$url" || fail "download failed: $url"
    fi
    dpkg-deb -x "$f" "$TOOLS_DIR/root/" || fail "extract failed: $f"
  done
  [ -x "$TOOLS_DIR/root/usr/bin/proot" ] || fail "proot missing after bootstrap"
  [ -x "$TOOLS_DIR/root/usr/bin/qemu-aarch64" ] || fail "qemu-aarch64 missing after bootstrap"
  [ -x "$TOOLS_DIR/root/usr/bin/qemu-arm" ] || fail "qemu-arm missing after bootstrap"
  log "✅ tools ready: proot 5.4.0 + qemu-user 10.0.13 (static) in $TOOLS_DIR"
}
setup_tools

if [ "$GUEST_MODE" = "proot" ]; then
  PROOT="$TOOLS_DIR/root/usr/bin/proot"
  export LD_LIBRARY_PATH="$TOOLS_DIR/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  # seccomp 加速会放行 qemu 的部分新式系统调用（未翻译路径）→ 必须关闭（实测 2026-09）
  export PROOT_NO_SECCOMP=1
fi

ROOTFS="$WORK_DIR/$ARCH/rootfs"
OUT_DIR="$WORK_DIR/out"
OUT_TARBALL="$OUT_DIR/apex-ubuntu-full-$POINT_VERSION-$UBUNTU_ARCH.tar.gz"
BASE_TARBALL="$WORK_DIR/$ARCH/ubuntu-base-$POINT_VERSION-base-$UBUNTU_ARCH.tar.gz"
BASE_SHA="$(eval "echo \$BASE_SHA256_$(echo "$ARCH" | tr 'a-z' 'A-Z')")"
STAGE_MARKER="$WORK_DIR/$ARCH/.stage-"

# guest 执行器双路径：
#   chroot —— 真实 root（CI）；挂载编队 guest_mounts_up/down 维护 /proc /dev /sys
#   proot  —— -0 伪 root；-q 取宿主侧解释器路径（实测）。qemu 模拟下 glibc 堆
#             稳定化（单 arena + 关 tcache —— 32 位 qemu 堆翻译误判兑底）
GUEST_ARGS=(-R "$ROOTFS" -0)
[ -n "$QEMU_BIN" ] && GUEST_ARGS+=(-q "$TOOLS_DIR/root/usr/bin/$QEMU_BIN")

# chroot 挂载编队（幂等）：resolv.conf 先删后拷 —— 避免透过悬空符号链接写入。
QEMU_BINDS=()
# binfmt 解释器在 chroot 根内解析：Ubuntu qemu-user-static 注册无 F 标志 →
# 必须把解释器以「文件级 bind」镜像到 rootfs 同路径（绝不整目录 bind ——
# 若解释器在 /usr/bin 下，整目录 bind 会把宿主 /usr/bin 灌进 guest，灾难）。
ensure_qemu_interp_visible() {
  local h interp flags
  for h in /proc/sys/fs/binfmt_misc/qemu-*; do
    [ -f "$h" ] || continue
    interp="$(sed -n 's/^interpreter //p' "$h" 2>/dev/null)"
    flags="$(sed -n 's/^flags[: ]* *//p' "$h" 2>/dev/null)"
    [ -n "$interp" ] || continue
    case "$flags" in *F*) continue ;; esac   # F：内核已缓存解释器 fd，跨命名空间可用
    [ -e "$ROOTFS$interp" ] && continue       # 已可见（重入幂等）
    "${SUDO_CMD[@]}" mkdir -p "$ROOTFS$(dirname "$interp")"
    "${SUDO_CMD[@]}" touch "$ROOTFS$interp"
    "${SUDO_CMD[@]}" mount --bind "$interp" "$ROOTFS$interp"
    QEMU_BINDS+=("$ROOTFS$interp")
  done
}
guest_mounts_up() {
  [ "$GUEST_MODE" = "chroot" ] || return 0
  # ubuntu-base 骨架无 /tmp、/var/tmp（tar 实测 3413 条目无此二目录；proot -R 曾
  # 自动 bind 宿主 /tmp 掩盖 —— 真实 chroot 必须显式存在且 1777，否则 apt-key
  # 建不了临时文件 → 全部套件 GPG 校验失败「repository is not signed」）
  "${SUDO_CMD[@]}" mkdir -p "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/run"
  "${SUDO_CMD[@]}" chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp"
  mountpoint -q "$ROOTFS/proc" || "${SUDO_CMD[@]}" mount --bind /proc "$ROOTFS/proc"
  mountpoint -q "$ROOTFS/dev"  || "${SUDO_CMD[@]}" mount --bind /dev  "$ROOTFS/dev"
  mountpoint -q "$ROOTFS/sys"  || "${SUDO_CMD[@]}" mount --bind /sys  "$ROOTFS/sys"
  ensure_qemu_interp_visible
  "${SUDO_CMD[@]}" rm -f "$ROOTFS/etc/resolv.conf"
  "${SUDO_CMD[@]}" cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
}
# 卸载双保险（clean 打包前 + trap EXIT）：挂载残留会让 tar 打进宿主 /proc、
# rm -rf $ROOTFS/dev/* 误删宿主 /dev —— 均为灾难级事故。
guest_mounts_down() {
  [ "$GUEST_MODE" = "chroot" ] || return 0
  local d
  for d in "${QEMU_BINDS[@]:-}"; do
    [ -n "$d" ] || continue
    "${SUDO_CMD[@]}" umount "$d" 2>/dev/null || true
    "${SUDO_CMD[@]}" rm -f "$d" 2>/dev/null || true      # 拦截占位文件进产物
  done
  QEMU_BINDS=()
  "${SUDO_CMD[@]}" umount "$ROOTFS/sys" "$ROOTFS/dev" "$ROOTFS/proc" 2>/dev/null || true
}
trap guest_mounts_down EXIT

run_in_guest() {
  [ -d "$ROOTFS" ] || fail "rootfs missing — run 'prepare' stage first"
  if [ "$GUEST_MODE" = "chroot" ]; then
    guest_mounts_up
    "${SUDO_CMD[@]}" chroot "$ROOTFS" /usr/bin/env -i \
      HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin \
      DEBIAN_FRONTEND=noninteractive \
      LANG=C.UTF-8 /bin/bash -c "$1"
  else
    "$PROOT" "${GUEST_ARGS[@]}" /usr/bin/env -i \
      HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin \
      DEBIAN_FRONTEND=noninteractive \
      MALLOC_ARENA_MAX=1 GLIBC_TUNABLES=glibc.malloc.tcache_count=0 \
      LANG=C.UTF-8 /bin/bash -c "$1"
  fi
}

run_stage() { [ "$STAGE" = "all" ] || [ "$STAGE" = "$1" ]; }
stage_done() { touch "${STAGE_MARKER}$1"; }
stage_was_done() { [ -f "${STAGE_MARKER}$1" ]; }

# ─────────────────────────────────────────────────────────────────────────────
# prepare：官方 ubuntu-base → 解包 → 注入构建源 + policy-rc.d
# ─────────────────────────────────────────────────────────────────────────────
# proot 下 maintainer script 的两个实测坑（2026-09 本地 arm64 全量安装验证）：
#   ① tzdata postinst 的 `mv /etc/localtime.dpkg-new /etc/localtime` 在
#      rename-over-已存在目标 时报 Permission denied（容器 overlayfs + proot 组合）
#      → 预删 /etc/localtime，让 mv 变成「创建式 rename」；tzdata 失败会级联卡死
#      python3.12 / libpython3.12-stdlib / vim（依赖 tzdata）的 configure。
#   ② openssh-client postinst 跑 adduser，要开 /run/adduser 锁文件 —— ubuntu-base
#      骨架没有 /run 目录 → 预建。（prepare 与 install 阶段都做：install 可断点续跑。）
fix_proot_script_traps() {
  rm -f "$ROOTFS/etc/localtime" "$ROOTFS/etc/localtime.dpkg-new" 2>/dev/null || true
  mkdir -p "$ROOTFS/run"
  chmod 755 "$ROOTFS/run" 2>/dev/null || true
}

do_prepare() {
  if stage_was_done prepare; then log "prepare already done (marker present) — skip"; return 0; fi
  mkdir -p "$WORK_DIR/$ARCH" "$OUT_DIR"

  if [ ! -f "$BASE_TARBALL" ] || [ "$(sha256sum "$BASE_TARBALL" | cut -d' ' -f1)" != "$BASE_SHA" ]; then
    rm -f "$BASE_TARBALL"
    log "downloading ubuntu-base $POINT_VERSION $UBUNTU_ARCH (~30MB)"
    curl -fL --retry 3 --connect-timeout 30 -o "$BASE_TARBALL" \
      "$BASE_MIRROR/ubuntu-base-$POINT_VERSION-base-$UBUNTU_ARCH.tar.gz" \
      || curl -fL --retry 3 --connect-timeout 30 -o "$BASE_TARBALL" \
      "$BASE_OFFICIAL/ubuntu-base-$POINT_VERSION-base-$UBUNTU_ARCH.tar.gz" \
      || fail "ubuntu-base download failed"
    local actual
    actual="$(sha256sum "$BASE_TARBALL" | cut -d' ' -f1)"
    [ "$actual" = "$BASE_SHA" ] || fail "ubuntu-base digest mismatch (expected=$BASE_SHA actual=$actual)"
  fi
  log "ubuntu-base $UBUNTU_ARCH verified (sha256=${BASE_SHA:0:12}…)"

  rm -rf "$ROOTFS"
  mkdir -p "$ROOTFS"
  log "extracting base skeleton"
  tar -xzf "$BASE_TARBALL" -C "$ROOTFS"

  # ubuntu-base 用 deb822（/etc/apt/sources.list.d/ubuntu.sources）——整文件改写为构建镜像
  local apt_path
  case "$UBUNTU_ARCH" in
    arm64|armhf) apt_path="$MIRROR/ubuntu-ports" ;;
    amd64)       apt_path="$MIRROR/ubuntu" ;;
  esac
  cat > "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" <<EOF
Types: deb
URIs: $apt_path
Suites: noble noble-updates noble-security noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
  : > "$ROOTFS/etc/apt/sources.list"
  # 绝不让构建期的 postinst 起服务（chroot 惯例；proot 下服务必挂）
  printf '#!/bin/sh\nexit 101\n' > "$ROOTFS/usr/sbin/policy-rc.d"
  chmod 755 "$ROOTFS/usr/sbin/policy-rc.d"
  stage_done prepare
  log "✅ prepare done"
}

# ─────────────────────────────────────────────────────────────────────────────
# update：apt-get update（幂等可重跑）
# ─────────────────────────────────────────────────────────────────────────────
do_update() {
  log "apt-get update (build mirror via $MIRROR)"
  # qemu 下 gpgv 状态输出偶发截断（「Good signature, but could not determine
  # key fingerprint」—— 同一 run 内 4 套件仅 1 个失败，传输截断/模拟时序问题，
  # 重跑即愈；实测 2026-09 CI）。套件级失败不被 Acquire::Retries 覆盖，
  # 必须整命令重试。
  local attempt rc
  for attempt in 1 2 3; do
    if run_in_guest "apt-get -o Acquire::Retries=5 update"; then
      stage_done update
      log "✅ update done (attempt $attempt)"
      return 0
    fi
    rc=$?
    log "⚠ apt-get update attempt $attempt/3 failed (rc=$rc) — retrying in 20s"
    [ "$attempt" -lt 3 ] && sleep 20
  done
  fail "apt-get update failed after 3 attempts"
}

# ─────────────────────────────────────────────────────────────────────────────
# install：全清单安装（可断点续跑 —— 中断后重跑自愈）
# ─────────────────────────────────────────────────────────────────────────────
do_install() {
  local pkgs n
  pkgs="$(grep -vE '^\s*(#|$)' "$PACKAGES_FILE" | tr '\n' ' ')"
  n="$(grep -vcE '^\s*(#|$)' "$PACKAGES_FILE")"
  log "installing $n packages (superset of BasePackageProfile): $pkgs"
  # proot maintainer-script 陷阱预修复（见 do_prepare 上方注释 —— 续跑场景 prepare 不会再执行）
  fix_proot_script_traps
  # 自愈中断：上次 install 被 timeout/杀进程打断时 dpkg 处于半配置态
  run_in_guest "dpkg --configure -a" || true
  run_in_guest "apt-get -o Acquire::Retries=5 install -y --no-install-recommends $pkgs" \
    || fail "apt-get install failed (re-run this stage to resume)"
  # 校验：全部包确已安装（防部分成功被误判为完成）
  local missing
  missing="$(run_in_guest "for p in $pkgs; do dpkg-query -W -f '\${Status}\n' \"\$p\" 2>/dev/null | grep -q 'install ok installed' || echo \"\$p\"; done")" || true
  if [ -n "$missing" ]; then fail "packages not installed after install stage: $missing"; fi
  stage_done install
  log "✅ install done (all $n packages verified installed)"
}

# ─────────────────────────────────────────────────────────────────────────────
# locale：预生成 en_US.UTF-8 + zh_CN.UTF-8（设备端零成本中文 locale）
# ─────────────────────────────────────────────────────────────────────────────
do_locale() {
  if stage_was_done locale; then log "locale already generated — skip"; return 0; fi
  log "generating locales (en_US.UTF-8 + zh_CN.UTF-8)"
  run_in_guest "locale-gen en_US.UTF-8 zh_CN.UTF-8 && update-locale LANG=en_US.UTF-8" \
    || fail "locale-gen failed"
  stage_done locale
  log "✅ locale done"
}

# ─────────────────────────────────────────────────────────────────────────────
# clean：净化（体积 + 隐私 + 回写官方源）
# ─────────────────────────────────────────────────────────────────────────────
do_clean() {
  if stage_was_done clean; then log "clean already done — skip"; return 0; fi
  log "cleaning apt caches / logs / build-time injections"
  run_in_guest "apt-get clean && rm -rf /var/lib/apt/lists/* /var/cache/apt/*.bin /var/backups/*" || true
  # 卸载 chroot 挂载 —— 必须在 rm -rf "$ROOTFS/dev"/* 之前（挂载态会误删宿主 /dev）
  guest_mounts_down
  # chroot 真实 root 下 dpkg 产物归 root：宿主侧清理/打包/删目录会吃权限。
  # tar 打包本就强制 --owner=0 --group=0（归属与产物无关）——此处把 fs 属主
  # 还给当前调用者，后续宿主侧操作不再有权限分叉（proot 模式文件本就归调用者）。
  [ "$GUEST_MODE" = "chroot" ] && "${SUDO_CMD[@]}" chown -R "$(id -u):$(id -g)" "$ROOTFS"
  rm -f "$ROOTFS/usr/sbin/policy-rc.d"
  # 回写官方源（设备端 UbuntuSourcesList 会按镜像开关再改写；默认值给官方）
  rm -f "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources"
  cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb http://ports.ubuntu.com/ubuntu-ports/ noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports/ noble-security main restricted universe multiverse
EOF
  [ "$UBUNTU_ARCH" = "amd64" ] && sed -i 's|ports.ubuntu.com/ubuntu-ports|archive.ubuntu.com/ubuntu|g' "$ROOTFS/etc/apt/sources.list"
  # 中性默认 resolv.conf / hosts（设备端 RootfsConfigurator 首次解包即重写）
  # resolv.conf 必须留空：RootfsConfigurator 只在「无内容」时注入 Android 系统 DNS ——
  # 写死 8.8.8.8 会压制设备端注入，受限网络下解析变慢（2026-09 审查发现）。
  : > "$ROOTFS/etc/resolv.conf"
  printf '127.0.0.1\tlocalhost\n' > "$ROOTFS/etc/hosts"
  # 机器标识/日志/临时目录清零（隐私 + 避免把构建环境带进产物）
  : > "$ROOTFS/etc/machine-id" 2>/dev/null || true
  rm -f "$ROOTFS/var/lib/dbus/machine-id"
  find "$ROOTFS/var/log" -type f -exec truncate -s 0 {} + 2>/dev/null || true
  rm -rf "$ROOTFS/tmp"/* "$ROOTFS/var/tmp"/* "$ROOTFS/run"/* "$ROOTFS/root/.bash_history" 2>/dev/null || true
  rm -rf "$ROOTFS/dev"/* 2>/dev/null || true   # proot 会话期 /dev 为宿主 bind；产物保持空骨架
  stage_done clean
  log "✅ clean done"
}

# ─────────────────────────────────────────────────────────────────────────────
# package：tar.gz 打包 + 指纹报告（root 属主 + 名字序，可复核）
# ─────────────────────────────────────────────────────────────────────────────
do_package() {
  if [ -f "$OUT_TARBALL" ]; then log "package already exists: $OUT_TARBALL — remove to rebuild"; return 0; fi
  log "packaging $OUT_TARBALL (~$(du -sh "$ROOTFS" | cut -f1) unpacked)"
  local unpacked_bytes
  unpacked_bytes="$(du -s --block-size=1 "$ROOTFS" | cut -f1)"
  tar --numeric-owner --owner=0 --group=0 --sort=name \
      -C "$ROOTFS" -czf "$OUT_TARBALL" .
  local digest size
  digest="$(sha256sum "$OUT_TARBALL" | cut -d' ' -f1)"
  size="$(stat -c%s "$OUT_TARBALL")"
  # 指纹报告（CI publish 作业归并为 rootfs-digests.txt 附到托管 Release）：
  # Kotlin 注册表 / fetch_rootfs.sh / rootfs-bundle.sha256 三处真值表由此钉值。
  local report="$OUT_DIR/apex-ubuntu-full-$POINT_VERSION-$UBUNTU_ARCH.digest.txt"
  {
    echo "arch=$UBUNTU_ARCH"
    echo "sha256=$digest"
    echo "size=$size"
    echo "unpacked=$unpacked_bytes"
  } > "$report"
  stage_done package
  log "✅ built $UBUNTU_ARCH full rootfs"
  log "   path    : $OUT_TARBALL"
  log "   size    : $(numfmt --to=iec "$size" 2>/dev/null || echo "${size}B")"
  log "   unpacked: $(numfmt --to=iec "$unpacked_bytes" 2>/dev/null || echo "${unpacked_bytes}B")"
  log "   sha256  : $digest"
  log "   report  : $report"
  log "   → 钉入 scripts/fetch_rootfs.sh ARTIFACTS 表 + rootfs-bundle.sha256 + BundledRootfsSource.kt"
  # 清理解包目录（保留 tarball 供 fetch_rootfs.sh 暂存）
  rm -rf "$ROOTFS"
}

# ─────────────────────────────────────────────────────────────────────────────
# 驱动
# ─────────────────────────────────────────────────────────────────────────────
if run_stage prepare; then do_prepare; fi
if run_stage update;  then do_update;  fi
if run_stage install; then do_install; fi
if run_stage locale;  then do_locale;  fi
if run_stage clean;   then do_clean;   fi
if run_stage package; then do_package; fi
run_stage all && log "🎉 $UBUNTU_ARCH full rootfs pipeline complete"
exit 0

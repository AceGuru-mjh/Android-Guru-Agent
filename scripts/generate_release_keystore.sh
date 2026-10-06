#!/usr/bin/env bash
# ═════════════════════════════════════════════════════════════════════════════
# 生成发布签名 keystore（固定指纹，消除签名漂移）
# ═════════════════════════════════════════════════════════════════════════════
#
# 背景：v1.4.7 之前 release 构建用 signingConfigs.getByName("debug") ——
# debug.keystore 是机器本地的，CI runner 与开发者本机必然不同 →
# 增量更新（VCDIFF 合成）的 APK 字节级保留源 APK 签名块，但远端新版是 CI
# debug key 签的 → 覆盖安装必报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
#
# 修法：把发布签名固定为一份入库的 keystore（name=meng411722 / key=meng411722），
# CI 与本地构建共用同一份 → 签名指纹恒定 → 增量合成 APK 与已装 APK 签名一致。
#
# 本脚本幂等：keystore 已存在且指纹匹配则跳过；不存在则生成。
# 个人项目经 GitHub Releases 侧载分发（非 Play Store），入库 keystore 是
# 开源 Android 应用的标准做法（Termux / F-Droid 同款）。
# ═════════════════════════════════════════════════════════════════════════════
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KEYSTORE_DIR="$REPO_ROOT/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/apex-release.jks"

# ── 固定凭据（用户指定：name=meng411722 / key=meng411722）──
KEYSTORE_PASSWORD="meng411722"
KEY_ALIAS="meng411722"
KEY_PASSWORD="meng411722"
CN_NAME="meng411722"
# 50 年有效期（18250 天）—— 避免发布证书过期导致的更新断裂
VALIDITY_DAYS=18250

mkdir -p "$KEYSTORE_DIR"

# 已存在则校验密码 + 指纹，匹配则跳过
if [[ -f "$KEYSTORE_FILE" ]]; then
    echo "[keystore] 已存在：$KEYSTORE_FILE"
    if keytool -list -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
        echo "[keystore] ✅ 凭据校验通过（alias=$KEY_ALIAS 已存在且密码匹配）"
        keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" \
            | grep -E 'SHA256|Owner|Valid' | head -5
        exit 0
    else
        echo "[keystore] ⚠️ 已存在但凭据不匹配 —— 备份旧文件后重新生成"
        mv "$KEYSTORE_FILE" "$KEYSTORE_FILE.bak.$(date +%s)"
    fi
fi

echo "[keystore] 生成发布签名 keystore："
echo "  路径: $KEYSTORE_FILE"
echo "  alias: $KEY_ALIAS"
echo "  CN  : $CN_NAME"
echo "  有效期: $VALIDITY_DAYS 天（≈ 50 年）"

keytool -genkeypair \
    -keystore "$KEYSTORE_FILE" \
    -storetype PKCS12 \
    -storepass "$KEYSTORE_PASSWORD" \
    -keyalg RSA \
    -keysize 2048 \
    -alias "$KEY_ALIAS" \
    -keypass "$KEY_PASSWORD" \
    -validity "$VALIDITY_DAYS" \
    -dname "CN=$CN_NAME, OU=ApexAgent, O=ApexAgent, L=Unknown, ST=Unknown, C=CN"

echo "[keystore] ✅ 生成完成"
echo ""
echo "[keystore] 证书指纹（用于 version.json.signingCertSha256 比对基准）："
keytool -list -v -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" \
    | grep -E 'SHA256:' | head -1
echo ""
echo "[keystore] 提示：本 keystore 已入库（个人项目侧载分发，开源 Android 标准做法）。"
echo "[keystore] CI 与本地构建共用同一份 → release APK 签名指纹恒定 → 增量合成 APK 与已装 APK 签名一致。"

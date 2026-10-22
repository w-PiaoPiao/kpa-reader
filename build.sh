#!/bin/bash
# 口袋阅读 — 一键构建
# 用法：
#   ./build.sh          构建 release APK（已签名，可直接装）
#   ./build.sh debug    构建 debug APK
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
if [ ! -f local.properties ]; then
    echo "sdk.dir=$SDK" > local.properties
    echo "已生成 local.properties -> $SDK"
fi

TASK="${1:-release}"

# 签名自检：证书和密码都不入库，缺失时 release 包会退回 debug 签名
if [ "$TASK" != "debug" ]; then
    if [ ! -f release.keystore ]; then
        echo "⚠ 没有 release.keystore，release 包将使用 debug 签名"
        echo "  （仅供本机调试；要签出可覆盖升级的包，把证书放回项目根目录）"
    elif ! grep -q '^KEYSTORE_PASSWORD=' local.properties 2>/dev/null; then
        echo "⚠ local.properties 缺少签名配置，release 包将使用 debug 签名"
        echo "  补上这三行即可（换成你自己的证书信息）："
        echo "    KEYSTORE_PASSWORD=..."
        echo "    KEY_ALIAS=..."
        echo "    KEY_PASSWORD=..."
    fi
fi

if [ "$TASK" = "debug" ]; then
    gradle :app:assembleDebug
    echo "完成 → app/build/outputs/apk/debug/app-debug.apk"
else
    gradle :app:assembleRelease
    echo "完成 → app/build/outputs/apk/release/app-release.apk"
fi

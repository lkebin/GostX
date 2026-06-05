#!/usr/bin/env bash
# update_lists.sh - 下载并转换 gfwlist 和中国大陆 IP 列表
#
# 用法：
#   ./scripts/update_lists.sh [输出目录]
#
# 输出目录默认为 ~/Downloads/gost-lists
#
# 生成文件后，推送到 Android 设备：
#   adb push <输出目录>/gfwlist_domains.txt /data/user/0/cn.liukebin.gostx/files/
#   adb push <输出目录>/china_ip_list.txt   /data/user/0/cn.liukebin.gostx/files/

set -eo pipefail

OUTPUT_DIR="${1:-$HOME/Downloads/gost-lists}"

GFWLIST_URL="https://raw.githubusercontent.com/gfwlist/gfwlist/master/gfwlist.txt"
CHINA_IP_URL="https://raw.githubusercontent.com/17mon/china_ip_list/master/china_ip_list.txt"

mkdir -p "$OUTPUT_DIR"

# ---------------------------------------------------------------------------
# gfwlist → 以 "." 开头的域名列表（gost bypass 格式，匹配域名及其所有子域）
# 原始格式为 base64 编码的 Adblock Plus 规则：||domain.com^
# ---------------------------------------------------------------------------
echo "📥 下载并转换 gfwlist..."

curl -fsSL "$GFWLIST_URL" \
  | base64 -d \
  | grep '^||' \
  | sed 's/^||//; s/[\/^].*//' \
  | grep -E '^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$' \
  | sed 's/^/./' \
  | sort -u \
  > "$OUTPUT_DIR/gfwlist_domains.txt"

COUNT=$(wc -l < "$OUTPUT_DIR/gfwlist_domains.txt" | tr -d ' ')
echo "   ✅ $COUNT 条域名 → $OUTPUT_DIR/gfwlist_domains.txt"

# ---------------------------------------------------------------------------
# china_ip_list → CIDR 列表（已是 gost bypass 格式，直接保存）
# ---------------------------------------------------------------------------
echo "📥 下载中国大陆 IP 列表..."

curl -fsSL "$CHINA_IP_URL" > "$OUTPUT_DIR/china_ip_list.txt"

COUNT=$(wc -l < "$OUTPUT_DIR/china_ip_list.txt" | tr -d ' ')
echo "   ✅ $COUNT 条 CIDR → $OUTPUT_DIR/china_ip_list.txt"

# ---------------------------------------------------------------------------
# 推送提示
# ---------------------------------------------------------------------------
echo ""
echo "✅ 完成！"
echo ""
echo "推送到 Android 设备（需要 ADB）："
echo "  adb push \"$OUTPUT_DIR/gfwlist_domains.txt\" /data/user/0/cn.liukebin.gostx/files/"
echo "  adb push \"$OUTPUT_DIR/china_ip_list.txt\"   /data/user/0/cn.liukebin.gostx/files/"

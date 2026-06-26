# VpnService 披露合规设计

## 背景

Google Play 拒绝了 GostX 的提交，理由是无法确认 VpnService 使用是否符合允许的情形。Google 的 VpnService 政策要求：

1. 在 Google Play 商品详情中注明 VpnService 使用情形
2. 提供醒目披露声明并征得用户同意
3. 对从设备传送到 VPN 隧道端点的数据进行加密
4. VPN 必须作为核心功能

GostX 的 VpnService 确实是核心功能（整个 app 就是 VPN 代理客户端），加密由用户配置的代理协议提供。问题在于没有主动披露和获取用户同意。

## 方案

两步走：
1. **Play 商店描述** — 在 Google Play Console 商品详情中添加 VpnService 使用声明（用户已手动贴入）
2. **应用内首次同意弹窗** — 用户首次点击启动 VPN 时弹出披露说明，获取明确同意后才启动

## 应用内弹窗设计

### 触发时机

用户首次点击 FAB 启动 VPN 时。`SharedPreferences` 记录 `vpn_disclosure_accepted` 键值，一旦同意就不再弹出。

### 弹窗内容

- 标题：VPN 连接说明 / VPN Connection Notice
- 正文列出 VpnService 用途、流量路由方式、数据隐私、加密说明
- 两个按钮：取消 / 同意并启动
- 点击取消：关闭弹窗，不启动 VPN
- 点击同意并启动：写入 SharedPreferences，继续 VPN 启动流程

### 代码改动

| 文件 | 改动 |
|---|---|
| `HomeViewModel.kt` | 暴露 `vpnDisclosureAccepted` 状态，`toggleVpn()` 在未同意时设置标志让 UI 弹窗 |
| `HomeScreen.kt` | 新增 `VpnDisclosureDialog` composable，在 `showDisclosureDialog` 为 true 时显示 |
| `strings.xml` | 新增弹窗标题、正文、按钮文案（英文） |
| `values-zh/strings.xml` | 新增对应中文翻译 |

### 弹窗流程

```
用户点击 FAB → toggleVpn()
  → 检查 SharedPreferences: vpn_disclosure_accepted?
    → false: showDisclosureDialog = true → 显示弹窗
      → 用户点「同意并启动」→ 写入 SharedPreferences → 继续启动 VPN
      → 用户点「取消」→ 关闭弹窗，不启动
    → true: 直接启动 VPN
```

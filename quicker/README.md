# Quicker Link 配对动作

`Quicker Link 配对` 是 Android 客户端的电脑端配对与全局动作目录助手。它不是 WebSocket 代理；手机扫码后仍然直接通过局域网 WSS 连接 Quicker。

## 行为

- 读取当前 Quicker 的 WebSocket 开关、WSS 开关、端口和连接验证码
- 枚举活动的 RFC1918 私网 IPv4，并在多网卡时提供地址选择
- 使用 Quicker 自带的 QRCoder 在内存中生成包含当前动作 ID 的 `quickerlink://pair` 二维码
- 接收固定命令 `quickerlink:list-global-actions:v1`，返回 `_global` 面板中的动作 ID、名称、分组和原始顺序
- 排除配对动作自身，不返回动作源码、内部参数或应用专属动作
- 不联网、不写文件、不访问剪贴板，也不修改 Quicker 设置

二维码包含当前连接验证码。关闭窗口后不要保留截图，也不要把二维码发送给其他人。

## 文件

- [`QuickerLinkPairing.cs`](QuickerLinkPairing.cs)：可审计的 C# 源码
- [`QuickerLinkPairing.action2.json`](QuickerLinkPairing.action2.json)：完整 ActionItem2 导出

动作 ID 为 `7db7596b-3b46-4afc-ab07-c96309d30aa8`，已在 Quicker `2.1.4.0`、运行时 `10.0.9` 上验证。首次公开到 Quicker 动作库前，仓库不会假装提供一个尚不存在的分享链接。

## 使用

1. 在 Quicker 的“手机 APP / WebSocket 设置”中启用 WebSocket 和安全连接 WSS。
2. 运行 `Quicker Link 配对`。
3. 如果出现多个地址，选择与手机同一局域网的地址。
4. 在 Android App 的连接页面点击“扫描配对码”。
5. 连接成功后，在“动作”页面点击“同步全局动作”。

动作不会生成新的 Quicker 验证码；它编码的是当前配置，所以不会产生电脑与手机凭据不同步的问题。

“只返回全局动作”是目录过滤，不是 Quicker WebSocket 的权限隔离。手机执行同步项时仍使用 Quicker 官方 `action` 操作；请继续使用 WSS、连接验证码和可信局域网。

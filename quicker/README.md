# Quicker Link 动作

`Quicker Link` 是 Android 客户端的电脑端配对与面板动作目录助手。它不是 WebSocket 代理；手机扫码后仍然直接通过局域网 WSS 连接 Quicker。

## 行为

- 读取当前 Quicker 的 WebSocket 开关、WSS 开关、端口和连接验证码
- 枚举活动的 RFC1918 私网 IPv4，并在多网卡时提供地址选择
- 使用 Quicker 自带的二维码组件在内存中生成专用配对码
- 响应已配对客户端的目录请求，返回全局与通用新面板中的动作 ID、名称、来源、分组、原始顺序和右键菜单快捷参数
- 排除配对动作自身，不返回动作源码、内部变量或应用专属动作
- 不联网、不写文件、不访问剪贴板，也不修改 Quicker 设置

二维码包含当前连接验证码。关闭窗口后不要保留截图，也不要把二维码发送给其他人。

## 安装

从 Quicker 动作库安装 [`Quicker Link`](https://getquicker.net/Sharedaction?code=b02b2732-f087-4e45-416d-08deee3e76ba)。分享 ID：`b02b2732-f087-4e45-416d-08deee3e76ba`。

本目录只保留透明背景的动作图标源文件。配套动作独立发布，其维护源码和完整 ActionItem2 导出不随 Android 开源仓库分发。当前动作已在 Quicker `2.1.4.0`、运行时 `10.0.9` 上验证。

## 使用

1. 在 Quicker 的“手机 APP / WebSocket 设置”中启用 WebSocket 和安全连接 WSS。
2. 运行 `Quicker Link`。
3. 如果出现多个地址，选择与手机同一局域网的地址。
4. 在 Android App 的连接页面点击“扫描配对码”。
5. 扫码认证成功后，App 会自动读取一次全局与通用动作；以后可以在“动作”页面手动刷新。

动作不会生成新的 Quicker 验证码；它编码的是当前配置，所以不会产生电脑与手机凭据不同步的问题。

“只返回全局与通用动作”是目录过滤，不是 Quicker WebSocket 的权限隔离。手机执行同步项时仍使用 Quicker 官方 `action` 操作；请继续使用 WSS、连接验证码和可信局域网。

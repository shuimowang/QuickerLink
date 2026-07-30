# Quicker Link

[![Android CI](https://github.com/shuimowang/QuickerLink/actions/workflows/android.yml/badge.svg)](https://github.com/shuimowang/QuickerLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-146B52.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](https://developer.android.com/)

Quicker Link 是一个非官方的开源 Android 客户端，通过局域网 WebSocket 直连电脑上的 [Quicker](https://getquicker.net/)。你可以在手机上保存常用动作，然后点按执行，也可以向电脑复制或粘贴文本。

> 本项目与 Quicker 官方及北京立迩合讯科技有限公司无隶属或授权关系。Quicker 是其权利人的产品和商标。

## 功能

- 通过 `WSS` 或 `WS` 直连局域网中的 Quicker
- 连接验证码认证，异常断线自动重连
- 保存、编辑、删除动作快捷项
- 通过动作名称或 ID 执行动作，并传入文本参数
- 向电脑剪贴板复制文本，或粘贴到电脑当前窗口
- 接收 Quicker 发来的 `copy` 消息并写入手机剪贴板
- 使用 Android Keystore 加密保存连接验证码
- 无账号、无云端、无广告、无统计 SDK

## 使用

### 1. 配置 Quicker

在 Quicker 中打开：

```text
设置 -> 手机 APP / WebSocket 设置
```

开启 WebSocket 服务，记录电脑的局域网 IPv4、端口和连接验证码。建议同时开启安全连接 `WSS`，并确认 Windows 防火墙允许 Quicker 使用该端口。

### 2. 连接手机

手机与电脑连接到同一个可互访的局域网。在 Quicker Link 的“连接”页面填写：

- 电脑 IPv4，例如 `192.168.1.56`
- Quicker WebSocket 端口，默认常见值为 `668`，以实际设置为准
- 与 Quicker 设置一致的 `WSS/WS` 模式
- 连接验证码

连接成功后，顶部状态图标会变为绿色。

### 3. 添加动作

在“动作”页面点击“添加动作”，填写 Quicker 动作名称或 ID。动作参数会作为 Quicker 的内置字符串参数传入，组合动作中可通过 `{quicker_in_param}` 使用。

如果 Quicker 中存在同名动作，请使用动作 ID，避免目标不明确。

建议对关机、删除文件等高风险动作开启“执行前确认”。

## 安装预览版

`v0.1.0-alpha.1` 是使用项目专用 Release 密钥签名的早期 prerelease APK，用于测试，不代表已达到稳定生产版质量。GitHub Release 不会发布调试签名或未签名 APK。

如需试用，请从 [Releases](https://github.com/shuimowang/QuickerLink/releases) 同时下载 `quicker-link-v0.1.0-alpha.1-release.apk` 和同名 `.sha256` 文件。首次安装时，Android 可能要求允许安装来自浏览器或文件管理器的应用。

安装前计算下载文件的 SHA-256，并与 `.sha256` 文件中的 64 位十六进制值逐字符比较：

```powershell
(Get-FileHash -Algorithm SHA256 -LiteralPath ".\quicker-link-v0.1.0-alpha.1-release.apk").Hash.ToLowerInvariant()
```

```bash
sha256sum --check quicker-link-v0.1.0-alpha.1-release.apk.sha256
```

校验和只能确认下载内容与发布的字节一致；请仍然仅从项目的 GitHub Release 页获取首个可信版本。

当前版本只保证前台使用，不提供后台常驻服务。App 进入后台时会主动断开；重新打开后，仅在已保存完整连接凭据时自动连接上次使用的电脑。

## 网络与安全

- 默认使用 `WSS`。连接地址形如 `wss://192-168-1-56.lan.quicker.cc:668/ws`。
- App 在本地将上述主机名解析回用户填写的 IPv4，同时保留主机名完成 TLS 证书校验。
- `WS` 会明文传输验证码和命令，仅应在可信局域网中作为兼容模式使用。
- 本项目不会禁用 TLS 证书或主机名校验。
- 请勿将 Quicker WebSocket 端口直接暴露到公网。

更多说明见 [隐私说明](PRIVACY.md)、[安全策略](SECURITY.md) 和 [协议实现说明](docs/PROTOCOL.md)。

## 构建

要求：

- JDK 17
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

`assembleRelease` 在本地和常规 CI 中只用于确认 Release 变体可以编译，生成的未签名 APK 不得分发。常规 CI 只上传明确标记的 Debug 测试产物；`v*` tag 工作流会从 GitHub Secrets 临时注入专用密钥，仅在签名验证通过后创建 prerelease。维护者流程见 [发布指南](docs/RELEASING.md)。

## 项目状态

当前为专用 Release 密钥签名的早期 prerelease，聚焦局域网内稳定触发动作。暂不支持公网中转、后台常驻、文件传输、图片粘贴或自动发现电脑。

欢迎阅读 [贡献指南](CONTRIBUTING.md) 并提交 Issue 或 Pull Request。

## License

[MIT](LICENSE)

# Quicker Link

[![Android CI](https://github.com/shuimowang/QuickerLink/actions/workflows/android.yml/badge.svg)](https://github.com/shuimowang/QuickerLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-146B52.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84.svg)](https://developer.android.com/)

Quicker Link 是一个非官方的开源 Android 客户端，通过局域网 WebSocket 直连电脑上的 [Quicker](https://getquicker.net/)。你可以在手机上保存常用动作，然后点按执行，也可以向电脑复制或粘贴文本。

> 本项目与 Quicker 官方及北京立迩合讯科技有限公司无隶属或授权关系。Quicker 是其权利人的产品和商标。

## 功能

- 通过 `WSS` 加密直连局域网中的 Quicker
- 自动发现同一局域网中的 Quicker，保留手动地址作为高级选项
- 扫描 Quicker Link 专用配对二维码
- 运行配套 Quicker 动作，自动读取当前 WSS 设置并离线生成配对二维码
- 扫码连接后自动同步 Quicker 新面板中的全局与通用动作、分组与顺序
- 按 Quicker 分组浏览并搜索动作名称、分组或 ID
- 连接验证码认证，异常断线自动重连
- 保存、编辑、删除动作快捷项
- 通过动作名称或 ID 执行动作，并传入文本参数
- 向电脑剪贴板复制文本，或粘贴到电脑当前窗口
- 接收 Quicker 发来的 `copy` 消息并写入手机剪贴板
- 使用 Android Keystore 加密保存连接验证码
- 无账号、无云端、无广告、无统计 SDK
- 在“关于”页面手动检查 GitHub Release 更新，不进行后台或自动检查

## 使用

### 1. 配置 Quicker

在 Quicker 中打开：

```text
设置 -> 手机 APP / WebSocket 设置
```

开启 WebSocket 服务和安全连接 `WSS`，记录端口与连接验证码，并确认 Windows 防火墙允许 Quicker 使用该端口。

先在 Quicker 中导入 [`QuickerLinkPairing.action2.json`](quicker/QuickerLinkPairing.action2.json)。配套的 `Quicker Link` 动作只读取这些设置，不会替你修改端口或重新生成验证码；可审计源码位于 [`quicker`](quicker) 目录。

### 2. 连接手机

手机与电脑连接到同一个可互访的局域网。首选方式是在电脑上运行 `Quicker Link` 动作，然后在 App 的连接页面点击“扫描配对码”。如果电脑有多个局域网地址，先在二维码窗口中选择与手机同网段的地址。二维码在本机内存中生成，不会上传，但其中包含连接验证码，请勿截图或分享。新安装默认会使用 Android Keystore 加密保存验证码，并在扫码认证后自动同步全局与通用动作；可以在连接前关闭“加密保存并自动连接”。

也可以在“连接”页面填写连接验证码后点击“自动查找并连接”。自定义端口或无法发现时，可以展开“高级设置”手动填写 IPv4，或打开[网页版配对码生成器](https://shuimowang.github.io/QuickerLink/pairing.html)生成专用二维码。网页版只在浏览器本地处理输入且禁止外联；离线使用时请下载完整的 [`tools`](tools) 目录，并打开其中的 `pairing.html`。Quicker 会员中心“推送到电脑”页面的二维码属于云推送服务，不包含局域网 WSS 配置，不能用于本项目配对。

### 3. 同步与添加动作

扫码连接成功后，App 会通过配套动作读取 Quicker 新面板中的全局与通用动作，只同步动作 ID、名称、来源、分组和面板顺序；不会读取动作源码、动作内部参数或具体应用场景动作。配对动作本身不会出现在同步结果中。以后可以在“动作”页面点击“刷新面板动作”手动更新。

再次同步会更新重命名、来源和分组变化，并移除已经不在全局与通用新面板中的同步项。同步项的名称和目标由 Quicker 管理，手机端仍可单独设置传入参数和“执行前确认”。原有手工快捷项会保留；动作页支持按名称、分组或动作 ID 搜索。

也可以点击“添加动作”继续创建手工快捷项，填写 Quicker 动作名称或 ID。动作参数会作为 Quicker 的内置字符串参数传入，组合动作中可通过 `{quicker_in_param}` 使用。

如果 Quicker 中存在同名动作，请使用动作 ID，避免目标不明确。

建议对关机、删除文件等高风险动作开启“执行前确认”。

## 安装预览版

`v0.2.0-alpha.2` 是使用项目专用 Release 密钥签名的早期 prerelease APK，用于测试，不代表已达到稳定生产版质量。GitHub Release 不会发布调试签名或未签名 APK。

如需试用，请从 [Releases](https://github.com/shuimowang/QuickerLink/releases) 同时下载 `quicker-link-v0.2.0-alpha.2-release.apk` 和同名 `.sha256` 文件。首次安装时，Android 可能要求允许安装来自浏览器或文件管理器的应用。

安装前计算下载文件的 SHA-256，并与 `.sha256` 文件中的 64 位十六进制值逐字符比较：

```powershell
(Get-FileHash -Algorithm SHA256 -LiteralPath ".\quicker-link-v0.2.0-alpha.2-release.apk").Hash.ToLowerInvariant()
```

```bash
sha256sum --check quicker-link-v0.2.0-alpha.2-release.apk.sha256
```

校验和只能确认下载内容与发布的字节一致；请仍然仅从项目的 GitHub Release 页获取首个可信版本。

当前版本只保证前台使用，不提供后台常驻服务。App 进入后台时会主动断开；重新打开后，仅在已保存完整连接凭据时自动连接上次使用的电脑。

## 网络与安全

- 仅支持局域网内经过证书校验的 `WSS` 连接，并将目标限制为私有 IPv4。
- App 不提供明文 `WS` 降级选项。
- 自动发现带有候选、并发和超时上限；探测阶段不发送验证码，结果不明确时要求改用扫码或手动地址。
- 专用配对二维码包含连接验证码，应按凭据保护，不要截图或分享。
- 配套 Quicker 动作不联网、不写文件，只在本机生成配对信息，并按需返回全局与通用动作的显示目录。
- “只同步全局与通用动作”限制的是 App 获取和展示的目录，不是 Quicker WebSocket 的权限边界；已认证客户端若已知其他动作 ID，Quicker 本身仍可能允许执行。
- 本项目不会禁用 TLS 证书或主机名校验。
- 请勿将 Quicker WebSocket 端口直接暴露到公网。

更多说明见 [隐私说明](PRIVACY.md)、[安全策略](SECURITY.md) 和 [连接实现边界](docs/PROTOCOL.md)。

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

当前为专用 Release 密钥签名的早期 prerelease，聚焦局域网内稳定触发动作。暂不支持公网中转、后台常驻、文件传输、图片粘贴或后台自动同步；面板动作目录只在扫码首次连接或用户手动刷新时同步。

欢迎阅读 [贡献指南](CONTRIBUTING.md) 并提交 Issue 或 Pull Request。

## License

[MIT](LICENSE)

# Quicker Link

[![Android CI](https://github.com/shuimowang/QuickerLink/actions/workflows/android.yml/badge.svg)](https://github.com/shuimowang/QuickerLink/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-146B52.svg)](LICENSE)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84.svg)](https://developer.android.com/)

Quicker Link 是一个非官方的开源 Android 客户端，通过局域网 `WSS` 直连电脑上的 [Quicker](https://getquicker.net/)。你可以在手机上触发常用动作、查看电脑当前屏幕，并交换文本和小文件。

> 本项目与 Quicker 官方及北京立迩合讯科技有限公司无隶属或授权关系。Quicker 是其权利人的产品和商标。

## 功能

- 通过 `WSS` 加密直连局域网中的 Quicker
- 自动发现同一局域网中的 Quicker，保留手动地址作为高级选项
- 扫描 Quicker Link 专用配对二维码
- 运行配套 Quicker 动作，自动读取当前 WSS 设置并离线生成配对二维码
- 扫码连接后自动同步 Quicker 新面板中的全局与通用动作、分组与顺序
- 以每行 4 至 6 个的高密度图标网格浏览动作，通过顶部横向分组导航切换，并搜索当前分组的名称、分组或 ID
- 连接验证码认证，异常断线自动重连
- 手工动作可编辑、删除；同步动作可设置参数和执行前确认
- 从同步动作菜单终止仍在 Quicker 中运行的该动作
- 通过动作名称或 ID 执行动作，并传入文本参数
- 将 Quicker 动作右键菜单同步为可自由输入的参数快捷选项
- 点击后只把动作派发给 Quicker，不等待持续运行类动作结束
- 一次性获取电脑当前屏幕，在手机中预览并按需保存
- 读取电脑剪贴板文本，或把手机文本写入电脑剪贴板
- 在手机与电脑之间双向传输不超过 8 MiB 的文件，显示进度并支持取消
- 向电脑当前窗口粘贴文本
- 接收 Quicker 发来的 `copy` 消息并写入手机剪贴板
- 使用 Android Keystore 加密保存连接验证码
- 无账号、无云端、无广告、无统计 SDK
- 在“关于”页面手动检查更新，按需下载校验并打开 Android 系统安装器；不进行后台或自动检查

## 使用

### 1. 配置 Quicker

在 Quicker 中打开：

```text
设置 -> 手机 APP / WebSocket 设置
```

开启 WebSocket 服务和安全连接 `WSS`，记录端口与连接验证码，并确认 Windows 防火墙允许 Quicker 使用该端口。

先从 Quicker 动作库安装配套的 [`Quicker Link`](https://getquicker.net/Sharedaction?code=b02b2732-f087-4e45-416d-08deee3e76ba) 动作。它不会替你修改端口或重新生成验证码，并为已配对 App 提供动作目录和用户主动发起的屏幕、剪贴板及小文件操作。Android 客户端源码完整保留在本仓库；配套 Quicker 动作独立发布，其维护源码不随本仓库分发。

### 2. 连接手机

手机与电脑连接到同一个可互访的局域网。首选方式是在电脑上运行 `Quicker Link` 动作，然后在 App 的连接页面点击“扫描配对码”。如果电脑有多个局域网地址，先在二维码窗口中选择与手机同网段的地址。二维码在本机内存中生成，不会上传，但其中包含连接验证码，请勿截图或分享。新安装默认会使用 Android Keystore 加密保存验证码，并在扫码认证后自动同步全局与通用动作；可以在连接前关闭“加密保存并自动连接”。

也可以在“连接”页面填写连接验证码后点击“自动查找并连接”。自定义端口或无法发现时，可以展开“高级设置”手动填写 IPv4，或打开[网页版配对码生成器](https://shuimowang.github.io/QuickerLink/pairing.html)生成专用二维码。网页版只在浏览器本地处理输入且禁止外联；离线使用时请下载完整的 [`tools`](tools) 目录，并打开其中的 `pairing.html`。Quicker 会员中心“推送到电脑”页面的二维码属于云推送服务，不包含局域网 WSS 配置，不能用于本项目配对。

### 3. 同步与添加动作

扫码连接成功后，App 会通过配套动作读取 Quicker 新面板中的全局与通用动作，只同步动作 ID、名称、图标、来源、分组、面板顺序和动作右键菜单中的快捷参数；不会读取动作源码、动作内部变量或具体应用场景动作。配对动作本身不会出现在同步结果中。以后可以在“动作”页面点击“刷新面板动作”手动更新。

再次同步会更新重命名、来源、分组和快捷参数变化，并移除已经不在全局与通用新面板中的同步项。同步项的名称、目标和快捷参数由 Quicker 管理，手机端仍可自由输入当前参数、从快捷选项中选择参数，并设置“执行前确认”。同步项菜单还可终止该动作当前的运行实例。同步项不提供单独删除；请在 Quicker 面板中移除后刷新。原有手工快捷项会保留；动作页通过横向标签切换 Quicker 分组，并在当前分组内按名称、分组或动作 ID 搜索。

如果电脑尚未安装配套动作或动作版本过旧，App 会显示“打开动作网页”入口，由用户前往 Quicker 动作网页完成安装或更新；其他同步失败会保留实际错误信息供排查。

也可以点击“添加动作”继续创建手工快捷项，填写 Quicker 动作名称或 ID。动作参数会作为 Quicker 的内置字符串参数传入，组合动作中可通过 `{quicker_in_param}` 使用。

如果 Quicker 中存在同名动作，请使用动作 ID，避免目标不明确。

建议对关机、删除文件等高风险动作开启“执行前确认”。

### 4. 使用传输工具

连接成功后打开底部“传输”页面：

- “当前屏幕”只在点击时获取一张快照，不进行连续录屏；快照可放大预览或保存到手机。
- “文本”可以读取电脑剪贴板，也可以把编辑框内容写入电脑剪贴板。
- “发送文件”由 Android 系统文件选择器选取一个文件，并保存到电脑的“下载 / Quicker Link”。
- “接收文件”会在电脑上打开系统文件选择窗口，用户确认后才传到手机的“下载 / Quicker Link”。

文件本体经过当前局域网 `WSS` 连接分块传输，单个文件上限为 8 MiB。双方都先写入临时文件并校验完整 SHA-256，再发布到最终位置；分块阶段可以取消，进入最终保存后会短暂锁定取消。若保存确认响应恰好丢失，App 会提供“重新确认”，使用同一传输标识查询原结果，不会重复落盘。异常遗留的临时文件会自动清理。这是面向动作配套使用的小文件通道，不替代专业的大文件传输工具。

## 安装预览版

`v0.3.0-alpha.3` 是使用项目专用 Release 密钥签名的早期 prerelease APK，用于测试，不代表已达到稳定生产版质量。GitHub Release 不会发布调试签名或未签名 APK。

如需首次安装，请从 [Releases](https://github.com/shuimowang/QuickerLink/releases) 同时下载 `quicker-link-v0.3.0-alpha.3-release.apk` 和同名 `.sha256` 文件。首次安装时，Android 可能要求允许安装来自浏览器或文件管理器的应用。

安装前计算下载文件的 SHA-256，并与 `.sha256` 文件中的 64 位十六进制值逐字符比较：

```powershell
(Get-FileHash -Algorithm SHA256 -LiteralPath ".\quicker-link-v0.3.0-alpha.3-release.apk").Hash.ToLowerInvariant()
```

```bash
sha256sum --check quicker-link-v0.3.0-alpha.3-release.apk.sha256
```

校验和只能确认下载内容与发布的字节一致；请仍然仅从项目的 GitHub Release 页获取首个可信版本。

后续版本可在 App 的“关于”页面先点“手动检查更新”，确认版本后再点“下载并安装”。App 会下载 APK 与对应校验文件，并核对 SHA-256、包名、版本和签名证书；全部通过后才打开 Android 系统安装器。Android 最后仍会显示安装确认，首次使用此入口时还可能要求允许 Quicker Link 安装未知应用。检查、下载和安装都不会自动开始。

当前版本只保证前台使用，不提供后台常驻服务。App 进入后台时会主动断开；重新打开后，仅在已保存完整连接凭据时自动连接上次使用的电脑。

## 网络与安全

- 仅支持局域网内经过证书校验的 `WSS` 连接，并将目标限制为私有 IPv4。
- App 不提供明文 `WS` 降级选项。
- 自动发现带有候选、并发和超时上限；探测阶段不发送验证码，结果不明确时要求改用扫码或手动地址。
- 专用配对二维码包含连接验证码，应按凭据保护，不要截图或分享。
- 配套 Quicker 动作不连接第三方服务；它在电脑本机生成配对信息、返回全局与通用动作目录，并响应已配对 App 明确发起的动作终止、一次性屏幕快照、剪贴板读取和小文件传输请求。
- 手机不能浏览电脑目录。“接收文件”必须由电脑端用户通过系统文件选择窗口确认；“发送文件”只写入电脑的“下载 / Quicker Link”。
- 屏幕、剪贴板和文件数据只经过当前已认证的局域网 `WSS` 会话，不会上传到本项目或其他云端服务。
- 文件使用 64 KiB 分块、分块及完整 SHA-256 校验和 `.part` 临时文件；单文件上限 8 MiB，同一时间只执行一个手机端传输任务。
- 动作图标只接受 Quicker 本地渲染的 PNG 数据或 `files.getquicker.net` 的受限 HTTPS 图片地址；App 不加载动作提供的任意第三方图标域名。
- “只同步全局与通用动作”限制的是 App 获取和展示的目录，不是 Quicker WebSocket 的权限边界；已认证客户端若已知其他动作 ID，Quicker 本身仍可能允许执行。
- 本项目不会禁用 TLS 证书或主机名校验。
- 请勿将 Quicker WebSocket 端口直接暴露到公网。

更多说明见 [隐私说明](PRIVACY.md)、[安全策略](SECURITY.md) 和 [连接实现边界](docs/PROTOCOL.md)。

## 构建

要求：

- Android 10 或更高版本
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

当前为专用 Release 密钥签名的早期 prerelease，聚焦局域网内稳定触发动作和前台小规模数据交换。暂不支持公网中转、后台常驻、连续屏幕查看、文件夹或大文件传输、图片直接粘贴、断点续传或后台自动同步；面板动作目录只在扫码首次连接或用户手动刷新时同步。

欢迎阅读 [贡献指南](CONTRIBUTING.md) 并提交 Issue 或 Pull Request。

## 设计参考

局域网发现、设备交互和传输状态机的设计调研参考了 LANChat、drift、[Websocket.Client](https://github.com/Marfusios/websocket-client) 与 [Sefirah](https://github.com/shrimqy/Sefirah)。其中 LANChat 用于比较 Android、Windows、设备列表和文件交互，drift 用于比较 WebSocket 二进制分块和临时文件状态，Websocket.Client 用于比较发送队列与重连，Sefirah 只用于比较配对、前台服务和跨端 UI；Sefirah 当前核心传输是 TCP/UDP Socket，并非 WebSocket。

Quicker Link 没有复制这些项目的源码，也没有引入它们作为依赖。相关项目各自遵循其仓库许可证；本项目实现仍以 Quicker 官方 `WSS` 服务和自身严格协议边界为准。

## 开源边界与许可证

本仓库中的 Android 客户端、测试、构建流程和公开文档采用 [MIT](LICENSE) 许可证，便于独立审计 App 的网络、存储和执行行为。通过 Quicker 动作库独立发布的配套动作不属于本仓库，也不在本仓库的 MIT 授权范围内。

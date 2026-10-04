# Quicker Link

Quicker Link 是由困困君发布的非官方 Android 客户端，用手机连接电脑上的 [Quicker](https://getquicker.net/)，快速触发常用动作，并使用屏幕控制、窗口切换、文本与小文件传输等跨设备能力。

> 本项目与 Quicker 官方及北京立迩合讯科技有限公司无隶属或授权关系。Quicker 是其权利人的产品和商标。

## 下载

- [下载 v0.5.0-alpha.14 APK](https://github.com/shuimowang/QuickerLink/releases/tag/v0.5.0-alpha.14)
- [查看全部版本](https://github.com/shuimowang/QuickerLink/releases)
- [安装配套 Quicker Link 动作](https://getquicker.net/Sharedaction?code=b02b2732-f087-4e45-416d-08deee3e76ba)

当前预览版要求 Android 10 或更高版本。APK 使用项目专用 Release 证书签名，每个版本同时提供 SHA-256 校验文件。请只从本仓库的 Releases 页面下载安装。

首次安装时，Android 可能要求允许浏览器或文件管理器“安装未知应用”。应用不会自动更新；请在“关于”页面手动检查、下载，并在 Android 系统安装确认后完成升级。

## 开始使用

1. 在 Quicker 的“手机 APP / WebSocket 设置”中启用 WebSocket 服务和安全连接 `WSS`。推荐设置连接验证码，也支持留空。
2. 安装并运行配套的 [Quicker Link 动作](https://getquicker.net/Sharedaction?code=b02b2732-f087-4e45-416d-08deee3e76ba)。
3. 在手机上扫描动作窗口生成的配对二维码。
4. 首次连接后，应用会同步 Quicker 新面板中的 `_global` 与 `common` 动作；之后也可以手动刷新。

配套动作按 Quicker `2.2.25` 或更高版本验证。若 Quicker Link 动作页面提示有更新，请先更新动作，再使用新版本 APK。

局域网连接无需账号或自建服务器。需要在外网使用时，可以在应用中选配 [Quicker 官方远程推送](https://getquicker.net/Member/PushTools)；远程模式适合动作触发和低带宽应急控制，文件传输与流畅屏幕控制仍建议使用局域网连接。

### 校园网连接不上时

校园网里手机和电脑都显示 `10.x.x.x`，也不代表两台设备可以互相访问。很多校园 Wi‑Fi 会启用终端隔离、划分不同 VLAN，或阻止无线设备访问电脑端口。此时 App 日志若显示“连接电脑超时”，通常是在 WSS 握手之前就被网络拦截，并非验证码或动作目录错误。

可以按下面顺序排查：

1. 在电脑上确认 Quicker 的 WebSocket 服务仍在运行，端口与配对码中的端口一致，并检查 Windows 防火墙规则覆盖当前网络配置文件。
2. 优先用 Quicker Link 动作重新生成二维码，选择电脑当前与手机同一网络的 IPv4 地址；自动查找在大型校园网中只会做有限探测，二维码更可靠。
3. 让手机和电脑同时连接同一个私人路由器，或临时打开手机热点给电脑连接。若这样马上成功，就能确认原校园网络禁止终端互访。
4. 不方便改变网络时，可在 App 中启用 Quicker 官方远程推送；它不依赖手机直接访问电脑的局域网端口。

如果日志显示“电脑拒绝了 WSS 连接”，优先检查 Quicker 服务、端口和防火墙；如果显示证书或主机名验证失败，再检查系统时间以及 VPN/HTTPS 拦截。请不要为了排查而把 WebSocket 端口暴露到公网。

## 主要功能

- 同一局域网自动发现、二维码配对和 `WSS` 加密连接
- 高密度动作面板，同步动作图标、分组和快捷参数
- 执行动作、传递参数，以及终止持续运行的动作
- 实时查看并点击电脑屏幕，查看和切换桌面窗口
- 手机与电脑互传文本、通知和不超过 64 MiB 的小文件
- 可从 Android 系统分享面板直接发送文本、图片和文件到电脑，支持多选文件顺序传输
- Android 后台连接，可接收电脑主动发来的文本、通知和文件邀请
- 睡眠、关机和重启 Quicker 等带二次确认的固定电脑控制
- 可选 Quicker 官方远程推送模式
- 应用内手动检查、校验和安装新版本

## 安全说明

- 局域网只提供 `WSS`，不提供明文 `WS` 降级。
- 请勿把 Quicker WebSocket 端口直接暴露到公网。
- 配对二维码可能包含连接验证码，请勿截图或分享。
- 远程模式只使用 Quicker 官方推送服务；本项目不运营账号系统或中转服务器。
- 手机不能浏览电脑目录，敏感电脑控制均需再次确认。
- 应用不包含广告或统计 SDK，不申请短信读取权限，也不提供系统代理或 VPN 功能。

## 项目说明

本公开仓库用于发布签名 APK、版本说明和收集问题，不包含 Android 客户端及配套 Quicker 动作的具体实现源码。历史上已经公开的版本仍按其发布时附带的许可处理。

发布人：[困困君](https://getquicker.net/User/Actions/743590-%E5%9B%B0%E5%9B%B0%E5%90%9B)

欢迎在 [Issues](https://github.com/shuimowang/QuickerLink/issues) 提交问题和建议，也欢迎通过作者主页支持作者。反馈问题时，请尽量附上 Android 版本、Quicker 版本、应用版本和经过脱敏的错误信息；不要公开连接验证码、配对二维码或个人文件内容。

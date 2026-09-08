# LocalSync 局域网同看 1.2.0

适用于 Minecraft 26.1.2、Fabric Loader 0.19.4、Java 25。

LocalSync 是独立实现的局域网同步媒体模组。房主打开单人世界并“对局域网开放”后，
集成服务器会成为权威同步节点，不经过外部验证服务器。模组同步媒体链接、播放时间轴
和影院屏幕位置，每个客户端使用 WaterMedia 在本地播放。

## 功能

- 在游戏内搜索 Bilibili 视频并从搜索结果直接同步播放。
- 在选定的竖直墙面渲染等比影院画面。
- 同步播放、暂停、跳转、继续、停止和中途加入进度。
- 支持普通媒体链接、Bilibili 视频页、分 P、分享文案和 `b23.tv` 短链。
- 每位玩家可以独立调整播放状态栏的位置、宽度和缩放。
- 局域网集成服务器和 Fabric 专用服务器均不依赖外部验证服务。

## 安装

从仓库的 **Releases** 页面下载 `localsync-1.2.0.jar`，不要下载 Source code 压缩包代替模组文件。

每位成员的 `mods` 目录都需要：

- `localsync-1.2.0.jar`
- `fabric-api-0.155.2+26.1.2.jar`
- `watermedia-3.0.0.23.jar`
- `watermedia_binaries-3.0.0.6.jar`
- `fabric-language-kotlin-1.13.12+kotlin.2.4.0.jar`

本模组未使用 LanCine 的代码或资源。它可以与 NekoVideo 并存，但不会调用 NekoVideo
的服务器或协议。

## 快速使用

1. 进入世界后看向竖直墙面的第一个角，按 `P`，在“播放与屏幕”中设置角点 1。
2. 看向同一墙面的对角并设置角点 2。两个角必须点在同一平面、同一面向。
3. 打开“视频搜索”，输入视频名、UP 主或关键词，点击结果即可同步播放；仍可在
   “播放与屏幕”中直接使用网页链接。
4. 在“状态栏”中用滑块调整位置、宽度和缩放，或直接拖动预览中的状态栏。

影院屏幕会保存在当前世界的 `data/localsync-screen.json`，下次进入同一世界会自动恢复。
从 1.1.1 升级后需要再设置一次两个角点，之后无需重复设置。

`P` 是正式按键绑定，可以在 Minecraft 控制设置的“LocalSync 局域网同看”分类中修改。
支持 WaterMedia 可解析的 HTTP/HTTPS 媒体、HLS 和网页链接，并额外支持 Bilibili
普通视频链接、分 P 链接与 `b23.tv` 短链。Bilibili 视频由每个客户端本地解析，使用
仅监听 `127.0.0.1` 的流代理补齐媒体请求头。状态栏布局保存在本机
`config/localsync-client.json`，不会影响朋友各自的界面布局。

## 命令

```text
/ls ui                 打开图形控制面板（也可按 P）
/ls play <链接>        全体播放
/ls pause              暂停全体
/ls resume             继续全体
/ls seek <秒>          全体快进或后退
/ls stop               停止全体
/ls screen pos1        将当前看向的墙面方块设为角点 1
/ls screen pos2        将当前看向的墙面方块设为角点 2
/ls screen clear       清除影院屏幕
/ls volume <0-100>     本地音量
/ls hide               本地显示或隐藏画面和 HUD
/ls flip               本地翻转视频画面
/ls status             当前同步状态
```

中途加入的成员会收到当前媒体、进度和影院屏幕。客户端每 0.5 秒检查播放漂移，超过
1.25 秒时自动纠偏；服务器每秒广播一次权威时间轴快照。

## 构建与测试

```powershell
.\build.ps1
.\test.ps1
.\test-bilibili.ps1
.\test-screen.ps1
.\test-hud.ps1
```

构建只使用本机 `D:\.minecraft` 中已有的 Minecraft、Fabric 和 WaterMedia 依赖。
产物位于 `build\libs\localsync-1.2.0.jar`。

# LocalSync 局域网同看 1.4.2

适用于 Minecraft 26.1.2、Fabric Loader 0.19.4、Java 25。

LocalSync 是独立实现的局域网同步媒体模组。房主打开单人世界并“对局域网开放”后，
集成服务器会成为权威同步节点，不经过外部验证服务器。模组只在玩家之间同步媒体链接、
播放时间轴和影院屏幕位置，每个客户端使用 WaterMedia 在本地播放。

## 功能

- 在游戏内按 Bilibili 网页的综合排序搜索视频，并从结果直接同步播放。
- 支持二维码或 Cookie 登录 Bilibili，并在游戏内浏览自己的收藏夹。
- 在选定的竖直墙面渲染等比影院画面。
- 请求账号可用的最高 Bilibili 清晰度，使用 WaterMedia 最高解码 LOD，并以线性采样缩放影院画面。
- 同步播放、暂停、跳转、继续、停止和中途加入进度。
- 支持普通媒体链接、Bilibili 视频页、分 P、分享文案和 `b23.tv` 短链。
- 视频结束后自动播放下一分 P、合集下一集或相关推荐视频。
- 每位玩家可以独立调整播放状态栏的位置、宽度和缩放。
- `P` 面板使用半透明玻璃风格；安装 ReGlass 2.0 后，播放状态 HUD 使用其原生 Liquid Glass shader。
- 局域网集成服务器和 Fabric 专用服务器均不依赖外部验证服务。

## 安装

从仓库的 **Releases** 页面下载 `localsync-1.4.2.jar`，不要下载 Source code 压缩包代替模组文件。

每位成员的 `mods` 目录都需要：

- `localsync-1.4.2.jar`
- `fabric-api-0.155.2+26.1.2.jar`
- `watermedia-3.0.0.23.jar`
- `watermedia_binaries-3.0.0.6.jar`
- `fabric-language-kotlin-1.13.12+kotlin.2.4.0.jar`

可选安装 `reglass-26.1-2.0.jar`。LocalSync 会通过 ReGlass 的公开 API 渲染真正的
Liquid Glass HUD；没有 ReGlass 时自动使用内置 fallback，不影响播放功能。

本模组未使用 LanCine 的代码或资源。它可以与 NekoVideo 并存，但不会调用 NekoVideo
的服务器或协议。

WaterMedia 及 WaterMedia Binaries 使用禁止再分发的 PolyForm Strict 许可证，因此不能
合并进 LocalSync 的公开 JAR。请从 WaterMedia 官方发布页单独安装这两个前置。

## 快速使用

1. 进入世界后看向竖直墙面的第一个角，按 `P`，在“播放”页设置角点 1。
2. 看向同一墙面的对角并设置角点 2。两个角必须点在同一平面、同一面向。
3. 打开“搜索”，输入视频名、UP 主或关键词；结果顺序与 Bilibili 网页的综合排序一致。
4. 点击搜索或收藏结果即可同步播放；也可以在“播放”页直接输入网页链接。
5. 在“界面”页用滑块调整状态栏位置、宽度和缩放，或直接拖动预览中的状态栏。

播放状态 HUD 注册在最低 HUD 层，并在物品栏、容器或其他菜单打开时自动隐藏，
因此不会遮挡 JEI 等界面模组。

影院屏幕会保存在当前世界的 `data/localsync-screen.json`，下次进入同一世界会自动恢复。
从 1.1.1 升级后需要再设置一次两个角点，之后无需重复设置。

`P` 是正式按键绑定，可以在 Minecraft 控制设置的“LocalSync 局域网同看”分类中修改。
支持 WaterMedia 可解析的 HTTP/HTTPS 媒体、HLS 和网页链接，并额外支持 Bilibili
普通视频链接、分 P 链接与 `b23.tv` 短链。Bilibili 视频由每个客户端本地解析，使用
仅监听 `127.0.0.1` 的流代理补齐媒体请求头。状态栏布局保存在本机
`config/localsync-client.json`，不会影响朋友各自的界面布局。

LocalSync 会请求 Bilibili 当前账号与片源允许的最高画质（包括 4K 标志），但实际返回
清晰度仍由 Bilibili 权限决定；未登录通常只能获得较低档位。影院画面的放大与缩小使用
线性采样，避免默认最近邻采样造成的明显像素块。

## Bilibili 账户与收藏

在 `P` 面板的“账号”页使用二维码或 Cookie 登录，然后在“收藏”页浏览收藏夹和视频。
登录状态保存在每位玩家自己的 `config/localsync-bilibili-account.json`。该文件会限制为
当前系统账户可访问，但 Cookie 仍以可读 JSON 保存，不是加密保险库；不要提交、分享或
上传这个文件。账户 Cookie 不会写入 LocalSync 的局域网同步数据包，也不会发给房主或
其他玩家。

## 自动连播

- 分 P 视频优先播放当前视频的下一分 P。
- UGC 合集按合集顺序播放下一集，到合集末尾后停止。
- 不属于分 P 或合集的 Bilibili 视频，会播放 Bilibili 返回的首个可用相关推荐。
- 普通直链和非 Bilibili 页面不会擅自选择下一条内容。

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
.\test-account.ps1
.\test-autoplay.ps1
.\test-cover-cache.ps1

# 一次运行完整离线测试
.\test-all.ps1

# 同时验证在线搜索、二维码和自动连播接口
.\test-all.ps1 -Live
```

构建只使用本机 `D:\.minecraft` 中已有的 Minecraft、Fabric 和 WaterMedia 依赖。
产物位于 `build\libs\localsync-1.4.2.jar`。

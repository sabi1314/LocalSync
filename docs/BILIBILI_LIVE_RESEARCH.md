# Bilibili 直播接入记录（1.5.2）

`1.5.2` 已按本文方案实现公共与私人直播播放入口。

## 已验证接口

- 房间规范化：`https://api.live.bilibili.com/room/v1/Room/room_init?id=ROOM_ID`
  可把短房间号转换为真实 `room_id`，并返回 `live_status`、锁定和加密状态。
- 房间信息：`https://api.live.bilibili.com/room/v1/Room/get_info?id=ROOM_ID`
  可用于标题、封面和开播状态。
- 播放流：`https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo`
  需要传入 `room_id`、`qn`、`protocol`、`format`、`codec`、`platform=web` 等参数。

2026-09-12 的实际响应确认播放接口同时提供：

- `http_stream` + FLV 和 `http_hls` + MPEG-TS HLS；
- AVC 与 HEVC；
- 多个 CDN 候选，完整地址由 `host + base_url + extra` 拼接；
- `g_qn_desc` 质量表，常见值包括 10000 原画、400 蓝光、250 超清、150 高清；
- `current_qn` 与 `accept_qn`，用于判断服务端真正授予的画质。

## 已实现方案

1. 识别 `live.bilibili.com/ROOM_ID`，先规范化房间号并检查 `live_status`。
2. 携带现有本机 Bilibili Cookie 请求 `getRoomPlayInfo`，优先原画，按实际
   `current_qn` 回退；不把 Cookie 或解析后的临时流地址同步给其他玩家。
3. 优先选择 WaterMedia/FFmpeg 兼容性更高的 AVC HLS，低延迟模式再评估 FLV；
   HEVC 作为设备能力允许时的可选项。
4. 为每个客户端独立解析 CDN 地址。共享数据包只广播房间页面 URL、播放意图和服务端
   开始时间，从而延续现有的隐私与 LAN 同步模型。
5. 监听播放器错误和停滞；CDN URL 过期、主播切流或网络中断时重新请求播放信息，并用
   有上限的指数退避重连。直播不执行 seek、队列结束或相关推荐逻辑。
6. 开播前增加离线、加密房间、地区限制、登录画质受限和音频-only 回退测试。

## 参考实现与资料

- [Bilibili 直播开放平台文档](https://open-live.bilibili.com/document/)
- [yt-dlp Bilibili extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bilibili.py)
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)

yt-dlp 的当前实现同样先检查房间状态，再按质量档请求 `getRoomPlayInfo`，遍历
`playurl_info.playurl.stream[].format[]` 并解析候选流。这适合作为行为验证参考，但实现时
仍应使用 LocalSync 自己的 Java 数据模型和现有 HTTP/账户组件。

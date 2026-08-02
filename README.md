# 猫鸡：Android 手机宠物 MVP

这是实体桌面宠物之前的个人单设备 MVP。Android 手机充当屏幕、麦克风、扬声器与控制面板；Linux 服务器负责云端语音识别、Hermes 对话、繁简转换和服务器端语音合成。手机通过 Tailscale 私网访问服务器，不依赖电脑一直开着。

## 当前已经实现

- 按住「按住命令猫鸡」录音，松开后上传 M4A 录音。
- 服务器将录音发送给腾讯云“一句话识别”转写中文，再交给独立 Hermes 会话 `pet-desk-02`。当前部署禁用了静默本地回退：腾讯云异常时会明确报错。
- Gateway 记录腾讯云成功请求的 `RequestId` 和音频时长，但不记录语音内容。密钥只存在服务器私有 `.env`，不会发送到 Android。
- 猫鸡人格及已确认的系统事实存放在 `gateway/SOUL.md`；创建专属 Hermes 会话时须把它注入该会话的系统提示词。仅使用 `--no-restore-cwd` 并不能保证已存在会话自动更新人格。
- Hermes 回复先被网关截断为最多 180 个字符，并用 OpenCC 转成繁體中文，再显示到手机气泡。
- 右下角喇叭默认关闭；打开后，手机请求 Gateway 的 `/v1/tts`，播放服务器生成的 MP3，而不是 Android 系统 TTS。
- `/v1/tts` 使用在线 Edge TTS 的中文女声，再由 FFmpeg 加入低采样率、颗粒、移相和回声，形成可听清的复古电子音；音频前后各有一声「喵～」。
- 喇叭上方的音乐按钮控制内置的 chiptune 背景音乐。音乐循环播放；关闭时暂停并记录本次 App 运行期间的进度，重新打开从该位置继续。彻底关闭 App 后会从头开始。
- 宠物会按待机、录音、思考、回答、失败与点击状态切换 GIF 动画。
- Gateway 使用 Tailscale 私网地址和独立 Bearer Token；腾讯云模式下录音仅以请求内存数据上传，不在 Gateway 磁盘落地；临时 TTS 文件会在处理后删除。

## 组件分工

| 部分 | 谁负责 | 当前做法 |
| --- | --- | --- |
| 录音、按住说话、画面与播放 | Android 应用 | `MediaRecorder` 录制 M4A；`GifView` 播放宠物动画；`MediaPlayer` 播放 Gateway TTS 和内置背景音乐。 |
| 私网连接 | Tailscale | Android 与 Linux 服务器在同一 Tailnet；App 直接访问服务器的 Tailscale IP。 |
| STT（语音转文字） | 腾讯云 + Linux Gateway | Gateway 将短 M4A 录音以 HTTPS 调用腾讯云“一句话识别”（`16k_zh`）；Android 不含腾讯云密钥。代码仍可选本地 `faster-whisper` 作为人工开启的备援，但当前部署已关闭该回退。 |
| 对话、待办和工具任务 | Hermes Agent | Gateway 以独立会话 `pet-desk-02` 调用 Hermes；该会话已注入 `SOUL.md`，规定猫鸡人格、繁體中文、短回复和当前 STT 事实。 |
| 繁體输出 | Linux 服务器 | `opencc-python-reimplemented` 将 Gateway 返回给手机的对话文字转为繁體。 |
| 朗读（TTS） | Linux 服务器 + Android 播放 | Gateway 调用 Edge TTS 和 FFmpeg，返回 MP3；Android 只下载并播放，不使用系统 Text-to-Speech。 |
| 背景音乐 | Android 应用 | 内置 `chiptune_bgm.mp3`，单声道 22.05kHz / 48kbps，循环播放；本次运行内支持断点续播。 |

## 宠物动画状态

- 待机：`idle` 为主，每 5–10 秒随机切换 `jumping`、`running_left`、`running_right` 等小动作，避免连续重复。
- 按住说话：立即切为 `review`；点击宠物不会中断录音。
- 上传、语音识别与 Hermes 思考：先显示 `waiting`，再每 3–5 秒在 `waiting`、`review`、`running` 间轮换。
- 回答到达：`jumping` 约 0.9 秒，接着 `waving` 约 2 秒，最后回待机。
- 点击宠物：待机时随机挥手或跳跃；思考时短暂挥手后继续原来的思考动画；失败时重播失败动画。
- 异常：显示 `failed` 约 2 秒，再回待机。

## 目录

```text
android/     Android Studio 原生 Java 应用、宠物素材、图标与背景音乐
gateway/     Linux 上常驻的 Python 网关、猫鸡 SOUL 和 systemd 用户服务
```

## 本地配置与安全

仓库不包含任何密钥、实际 Tailscale 地址、录音、生成的回复音频、APK、STT 模型或 Gradle 构建输出。

复制 `android/pet.properties.example` 为 `android/pet.properties`，填入私网网关地址和设备 Token。这个文件已被忽略，不能提交。

Linux 服务器的 `gateway/.env` 至少包括：

```ini
PET_GATEWAY_TOKEN=请生成一段长随机字符串
PET_GATEWAY_HOST=服务器的Tailscale_IP
PET_HERMES_SESSION=pet-desk-02
PET_STT_PROVIDER=tencent
# 生产环境建议 disabled：腾讯云失败时明确报错，不静默改用本地模型。
PET_STT_FALLBACK=disabled
TENCENT_SECRET_ID=仅保存在服务器的SecretId
TENCENT_SECRET_KEY=仅保存在服务器的SecretKey
TENCENT_ASR_ENGINE=16k_zh
# 可选；默认是 zh-CN-XiaoxiaoNeural
PET_TTS_VOICE=zh-CN-XiaoxiaoNeural
```

腾讯云 STT 路线只使用 Python 标准库签名和 HTTPS 请求；服务器还需要 `opencc-python-reimplemented`、`edge-tts`、FFmpeg，以及可用的 Hermes 命令。若主动设置 `PET_STT_PROVIDER=local` 或 `PET_STT_FALLBACK=local`，才需要额外安装 `faster-whisper` 及本地模型。详见 [gateway/README.md](gateway/README.md)。

## 运行与验证顺序

1. 服务器部署 `gateway/`，配置 `.env` 和 systemd 用户服务，并安装 Gateway 依赖与 FFmpeg。
2. 用 `POST /v1/warm` 检查 STT 配置；腾讯云模式不加载本地模型，只有本地模式才会预热模型。
3. Android Studio 打开 `android/`，创建本地 `pet.properties`，连接手机后安装 debug 包。
4. 在手机按住说话、松开，确认繁體文字回复出现；打开喇叭后确认服务器 TTS 播放；点击音乐按钮确认背景音乐循环和断点续播。

## 当前边界

- 这是个人单设备 MVP，不是多用户服务；Gateway 以锁串行处理 STT 与 Hermes 请求。
- 腾讯云“一句话识别”只适合短录音：Gateway 限制原始 M4A 不超过 2MB，且应控制在 60 秒内。语音识别和 Hermes 请求是同步的，复杂任务可能较久；App 的对话请求最长等待 6 分钟，TTS 请求最长等待 90 秒。
- Edge TTS 是在线服务：服务器需要能够访问它。它适合个人原型，不应视为具备正式服务等级保证的生产 TTS。
- 静音只关闭猫鸡回复音频，不影响录音、语音识别和文字回复；背景音乐与喇叭开关互相独立。
- 未来替换成 ESP32-S3 或其他实体终端时，可保留 Gateway、Tailscale、STT、Hermes 和 `/v1/tts`，替换 Android 客户端即可。

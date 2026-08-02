# Pet Gateway

这是猫鸡 Android MVP 的 Linux 私网网关。当前部署将短录音发往腾讯云“一句话识别”，然后调用独立 Hermes 会话 `pet-desk-02`。Android 只知道 Gateway 的 Bearer Token，不持有腾讯云密钥。

`SOUL.md` 是猫鸡的人格与系统事实来源。Hermes 已存在会话的系统提示词不会因为文件变化而自动更新；创建或更换猫鸡会话时，必须将该文件内容注入专属会话的系统提示词。

## 接口

所有接口都需要请求头：

```text
Authorization: Bearer <token>
```

| 接口 | 请求 | 实际作用 |
| --- | --- | --- |
| `POST /v1/audio` | 短 M4A 录音，`Content-Type: audio/mp4` | 主要调用腾讯云“一句话识别”转写中文，然后调用 Hermes；返回 JSON 的 `screen` 与 `speech`。 |
| `POST /v1/chat` | JSON：`{"text":"..."}` | 跳过 STT，直接调用 Hermes；返回相同 JSON。 |
| `POST /v1/tts` | JSON：`{"text":"..."}` | 调用 Edge TTS、加入前后「喵～」与 FFmpeg 复古电子效果；返回 `audio/mpeg`。 |
| `POST /v1/warm` | 空请求体 | 腾讯云模式检查密钥是否配置；本地模式预加载 Whisper 模型。 |
| `GET /health` | 无 | 返回网关存活状态。 |

腾讯云模式下，`/v1/audio` 的录音只在 Gateway 请求内存中编码并上传，不写入 Gateway 磁盘；`/v1/tts` 的临时音频只存在于临时目录，处理完成后删除。本地 Whisper 模式会临时落盘 M4A，并在调用完成后删除。

## 依赖

腾讯云路线不需要腾讯 SDK：`server.py` 以 Python 标准库实现腾讯云 TC3-HMAC-SHA256 签名，并通过 HTTPS 调用 `SentenceRecognition`。

```bash
./venv/bin/pip install opencc-python-reimplemented edge-tts
```

系统还需要 FFmpeg；它负责把 Edge TTS 的原始 MP3 处理为复古电子音。

- STT：生产配置为 `PET_STT_PROVIDER=tencent`，使用腾讯云“一句话识别”与 `16k_zh` 引擎。输入为 M4A；原始数据限制为 2MB，建议录音不超过 60 秒。每次成功会记录腾讯云 `RequestId` 与音频时长，不记录录音或识别文本。
- 本地备援：代码保留 `faster-whisper` 的本地 `base` 模型实现，只有 `PET_STT_PROVIDER=local`，或显式配置 `PET_STT_FALLBACK=local` 时才会启用。当前部署使用 `PET_STT_FALLBACK=disabled`，腾讯云失败会明确向 App 返回错误。
- 繁體输出：`opencc-python-reimplemented` 在网关侧将 Hermes 输出转为繁體。
- TTS：Edge TTS 的 `zh-CN-XiaoxiaoNeural` 女声为默认基底，可用 `PET_TTS_VOICE` 覆盖。TTS 需要服务器联网。

默认监听 `127.0.0.1:8787`；本项目的手机直连 MVP 通过 `PET_GATEWAY_HOST` 将其绑定到服务器 Tailscale IP。不要将此服务直接暴露到公网。

## 私有配置

`gateway/.env` 不进入 Git，权限应为仅服务账户可读。腾讯云配置示例：

```ini
PET_GATEWAY_TOKEN=请生成一段长随机字符串
PET_GATEWAY_HOST=服务器的Tailscale_IP
PET_HERMES_SESSION=pet-desk-02
PET_STT_PROVIDER=tencent
PET_STT_FALLBACK=disabled
TENCENT_SECRET_ID=仅保存在服务器
TENCENT_SECRET_KEY=仅保存在服务器
TENCENT_ASR_ENGINE=16k_zh
```

不要把 `TENCENT_SECRET_ID` 或 `TENCENT_SECRET_KEY` 写入 Android 的 `pet.properties`、APK、日志或仓库。

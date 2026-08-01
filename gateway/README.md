# Pet Gateway

这是猫鸡 Android MVP 的 Linux 私网网关。它在本目录调用独立 Hermes 会话 `pet-desk-01`；由于调用带有 `--no-restore-cwd`，本目录的 `SOUL.md` 会作为猫鸡人格规则加载。

## 接口

所有接口都需要请求头：

```text
Authorization: Bearer <token>
```

| 接口 | 请求 | 实际作用 |
| --- | --- | --- |
| `POST /v1/audio` | 短 M4A 录音，`Content-Type: audio/mp4` | 本地 faster-whisper 识别中文，然后调用 Hermes；返回 JSON 的 `screen` 与 `speech`。 |
| `POST /v1/chat` | JSON：`{"text":"..."}` | 跳过 STT，直接调用 Hermes；返回相同 JSON。 |
| `POST /v1/tts` | JSON：`{"text":"..."}` | 调用 Edge TTS、加入前后「喵～」与 FFmpeg 复古电子效果；返回 `audio/mpeg`。 |
| `POST /v1/warm` | 空请求体 | 预加载本地 STT 模型。 |
| `GET /health` | 无 | 返回网关存活状态。 |

`/v1/audio` 的录音和 `/v1/tts` 的临时音频都只存在于临时目录，处理完成后删除。

## 依赖

```bash
./venv/bin/pip install faster-whisper opencc-python-reimplemented edge-tts
```

系统还需要 FFmpeg；它负责把 Edge TTS 的原始 MP3 处理为复古电子音。

- STT：`faster-whisper` 的本地 `base` 多语言模型，以 CPU int8 运行，指定中文，不调用云端 STT。
- 繁體输出：`opencc-python-reimplemented` 在网关侧将 Hermes 输出转为繁體。
- TTS：Edge TTS 的 `zh-CN-XiaoxiaoNeural` 女声为默认基底，可用 `PET_TTS_VOICE` 覆盖。TTS 需要服务器联网。

默认监听 `127.0.0.1:8787`；本项目的手机直连 MVP 通过 `PET_GATEWAY_HOST` 将其绑定到服务器 Tailscale IP。不要将此服务直接暴露到公网。

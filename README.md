# Hermes 桌面宠物：Android 手机 MVP

这是“桌面宠物”硬件终端前的最小可用验证版本：Android 手机充当屏幕、麦克风、按住说话按钮与扬声器；家里的 Linux 服务器负责语音识别和 Hermes 对话。手机只通过 Tailscale 私网访问服务器，不依赖电脑一直开着。

## 现在能做什么

- 按住底部按钮录音，松开后把短语音发给服务器。
- 服务器识别中文语音，交给专用 Hermes 会话处理，再返回不超过 56 个字符的宠物回答。
- 手机展示回答，并尝试用 Android 系统 TTS 朗读。
- 宠物有待机与等待两种 GIF 动画。
- 网关只监听 Tailscale IP，接口还需要独立 Bearer Token；录音仅临时落盘，识别后立即删除。

## 组件怎么分工

| 部分 | 谁负责 | 做法 |
| --- | --- | --- |
| 录音、按住说话、画面 | Android 应用 | `MediaRecorder` 录制 M4A；`GifView` 播放 GIF；`MainActivity` 发起 HTTP 请求。 |
| 私网连接 | Tailscale | Android 与 Linux 服务器加入同一 Tailnet，应用直接访问服务器的 Tailscale IP。 |
| STT（语音转文字） | Linux 服务器 | `faster-whisper` 的本地 `base` 多语言模型，以 CPU int8 运行，指定中文识别；不调用云端 STT。 |
| 对话、待办和工具任务 | Hermes Agent | 网关以独立会话 `pet-desk-01` 调用 Hermes；其 `SOUL.md` 约束宠物身份、简短回复和可代办任务。 |
| 回答长度 | 网关 + SOUL | `SOUL.md` 要求短答，网关再硬性截断为 56 字，确保一页小屏可显示。 |
| 朗读（TTS） | 当前为 Android 系统 | 手机上的系统 Text-to-Speech 服务朗读返回文字。未来实体宠物可改为服务器 TTS 或 ESP32-S3 音频模块。 |

## 目录

```text
android/     Android Studio 原生 Java 应用
gateway/     Linux 上常驻的 Python 网关与 systemd 用户服务
```

## 本地配置与安全

仓库不包含任何密钥、实际 Tailscale 地址、录音、APK、模型或 Gradle 构建输出。

复制 `android/pet.properties.example` 为 `android/pet.properties`，填入私网网关地址和设备 Token。这个文件已被忽略，不能提交。

Linux 服务器把 Token 放在 `gateway/.env`，其内容至少包括：

```ini
PET_GATEWAY_TOKEN=请生成一段长随机字符串
PET_GATEWAY_HOST=服务器的Tailscale_IP
PET_HERMES_SESSION=pet-desk-01
```

`gateway/pet-gateway.service` 是 systemd 用户服务模板。先在服务器建立 Python 虚拟环境、安装 `faster-whisper`，并确认 Hermes 命令可用，再安装与启动该服务。

## 运行与验证顺序

1. 服务器安装 `faster-whisper`，部署 `gateway/`，配置 `.env` 与 systemd 服务。
2. 用 `POST /v1/warm` 预热本地 STT 模型；这样用户的第一次语音不必等待模型加载。
3. Android Studio 打开 `android/`，创建本地 `pet.properties`，连接手机后安装 debug 包。
4. 在手机按住说话、松开，等待“上传并识别语音”完成；服务器日志会记录 STT、Hermes 和总耗时。

## 当前边界

- 这是个人单设备 MVP，不是多用户服务；网关只适合一位用户串行使用。
- 语音识别和 Hermes 请求是同步的，复杂任务可能需要较久；手机当前最多等待 6 分钟。
- Android TTS 是否可用取决于手机是否装有可用的中文语音引擎；不影响服务器端 STT 与文字显示。
- 未来换成 ESP32-S3 屏幕宠物时，可保留网关、Tailscale、STT 和 Hermes，替换 Android 客户端即可。

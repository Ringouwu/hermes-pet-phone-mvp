# Pet Gateway

The Android MVP sends text to `POST /v1/chat` or a short WAV recording to `POST /v1/audio`, with `Authorization: Bearer <token>`.
The gateway invokes the separate Hermes session `pet-desk-01` from this directory, so its `SOUL.md` controls the short pet response.

`/v1/audio` runs local faster-whisper with the `base` multilingual model using CPU int8. The recording is held only in a temporary file and deleted immediately after transcription.

The gateway also uses `opencc-python-reimplemented` to convert every text reply to Traditional Chinese before returning it to the app:

```bash
./venv/bin/pip install faster-whisper opencc-python-reimplemented
```

It normally listens only on `127.0.0.1:8787`; set `PET_GATEWAY_HOST` to the machine's Tailscale IP for the temporary direct private-HTTP MVP.

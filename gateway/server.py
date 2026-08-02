#!/usr/bin/env python3
"""Private Android-to-Hermes gateway for the desk-pet MVP."""

from __future__ import annotations

import base64
import datetime as dt
import hashlib
import hmac
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from opencc import OpenCC

ROOT = Path(__file__).resolve().parent
TOKEN = os.environ.get("PET_GATEWAY_TOKEN", "")
HERMES = os.environ.get(
    "HERMES_BIN", str(Path.home() / ".hermes/hermes-agent/venv/bin/hermes")
)
SESSION = os.environ.get("PET_HERMES_SESSION", "pet-desk-01")
HOST = os.environ.get("PET_GATEWAY_HOST", "127.0.0.1")
STT_MODEL = os.environ.get("PET_STT_MODEL", "base")
STT_PROVIDER = os.environ.get("PET_STT_PROVIDER", "local").strip().lower()
STT_FALLBACK = os.environ.get("PET_STT_FALLBACK", "local").strip().lower()
TENCENT_SECRET_ID = os.environ.get("TENCENT_SECRET_ID", "")
TENCENT_SECRET_KEY = os.environ.get("TENCENT_SECRET_KEY", "")
TENCENT_ASR_ENGINE = os.environ.get("TENCENT_ASR_ENGINE", "16k_zh")
TTS_VOICE = os.environ.get("PET_TTS_VOICE", "zh-CN-XiaoxiaoNeural")
MAX_SCREEN_CHARS = 180
MAX_TENCENT_AUDIO_BYTES = 2 * 1024 * 1024
LOCK = threading.Lock()
MODEL = None
TRADITIONAL_CHINESE = OpenCC("s2t")


def compact_reply(value: str) -> str:
    value = TRADITIONAL_CHINESE.convert(" ".join(value.strip().split()))
    if len(value) > MAX_SCREEN_CHARS:
        value = value[: MAX_SCREEN_CHARS - 1].rstrip() + "…"
    return value or "我剛才沒有收到有效回答，再說一次試試。"


def ask_hermes(text: str) -> str:
    command = [
        HERMES, "chat", "--quiet", "--source", "pet", "--continue", SESSION,
        "--no-restore-cwd",
        "--max-turns", "4", "--query", text,
    ]
    with LOCK:
        completed = subprocess.run(
            command, cwd=ROOT, text=True, capture_output=True, timeout=240, check=False
        )
    if completed.returncode != 0:
        raise RuntimeError((completed.stderr or completed.stdout or "Hermes 調用失敗").strip())
    return compact_reply(completed.stdout)


def synthesize_speech(text: str) -> bytes:
    """Create one short, lightly retro-styled cat reply for the phone."""
    with tempfile.TemporaryDirectory(prefix="maoji-tts-") as folder:
        raw_path = Path(folder) / "raw.mp3"
        final_path = Path(folder) / "maoji.mp3"
        spoken = f"喵～。{text}。喵～"
        tts = subprocess.run(
            [
                sys.executable, "-m", "edge_tts", "--voice", TTS_VOICE,
                "--rate=-8%", "--text", spoken, "--write-media", str(raw_path),
            ],
            text=True, capture_output=True, timeout=60, check=False,
        )
        if tts.returncode != 0 or not raw_path.exists():
            raise RuntimeError("語音合成暫時不可用")
        effect = subprocess.run(
            [
                "ffmpeg", "-y", "-loglevel", "error", "-i", str(raw_path),
                "-af", "aresample=12000,highpass=f=220,lowpass=f=3400,"
                       "acrusher=bits=6:mode=lin:aa=1,"
                       "aphaser=in_gain=0.65:out_gain=0.9:delay=4:decay=0.75:speed=0.5,"
                       "aecho=0.8:0.55:70:0.20",
                "-ar", "12000", "-b:a", "32k", str(final_path),
            ],
            text=True, capture_output=True, timeout=30, check=False,
        )
        if effect.returncode != 0 or not final_path.exists():
            raise RuntimeError("語音效果處理失敗")
        return final_path.read_bytes()


def get_model():
    """Load the cached local STT model once, inside the gateway process."""
    global MODEL
    if MODEL is None:
        from faster_whisper import WhisperModel
        started = time.monotonic()
        print(f"stt: loading cached {STT_MODEL} model", flush=True)
        MODEL = WhisperModel(
            STT_MODEL,
            device="cpu",
            compute_type="int8",
            cpu_threads=4,
            num_workers=1,
            local_files_only=True,
        )
        print(f"stt: model ready in {time.monotonic() - started:.1f}s", flush=True)
    return MODEL


def transcribe_audio_locally(audio: bytes) -> str:
    """Transcribe one short phone recording locally; audio is deleted immediately."""
    with tempfile.NamedTemporaryFile(suffix=".m4a", delete=True) as handle:
        handle.write(audio)
        handle.flush()
        segments, _ = get_model().transcribe(
            handle.name, language="zh", beam_size=3, vad_filter=True, condition_on_previous_text=False
        )
        text = "".join(segment.text for segment in segments).strip()
    if not text:
        raise ValueError("沒有識別到有效語音")
    return text


def _hmac_sha256(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def transcribe_audio_with_tencent(audio: bytes) -> str:
    """Send one short M4A recording to Tencent Cloud SentenceRecognition.

    Credentials deliberately come only from the private service environment; the
    Android client receives neither the SecretId nor the SecretKey.
    """
    if not TENCENT_SECRET_ID or not TENCENT_SECRET_KEY:
        raise RuntimeError("Tencent Cloud STT credentials are not configured")
    if len(audio) > MAX_TENCENT_AUDIO_BYTES:
        raise ValueError("語音太長，請控制在 60 秒內再試一次")

    payload = json.dumps(
        {
            "ProjectId": 0,
            "SubServiceType": 2,
            "EngSerViceType": TENCENT_ASR_ENGINE,
            "SourceType": 1,
            "VoiceFormat": "m4a",
            "Data": base64.b64encode(audio).decode("ascii"),
            "DataLen": len(audio),
            "FilterDirty": 0,
            "FilterModal": 0,
            "FilterPunc": 0,
            "ConvertNumMode": 1,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")

    host = "asr.tencentcloudapi.com"
    service = "asr"
    action = "SentenceRecognition"
    version = "2019-06-14"
    timestamp = int(time.time())
    date = dt.datetime.fromtimestamp(timestamp, tz=dt.timezone.utc).strftime("%Y-%m-%d")
    content_type = "application/json; charset=utf-8"
    canonical_headers = f"content-type:{content_type}\nhost:{host}\n"
    signed_headers = "content-type;host"
    canonical_request = "\n".join(
        [
            "POST",
            "/",
            "",
            canonical_headers,
            signed_headers,
            hashlib.sha256(payload).hexdigest(),
        ]
    )
    credential_scope = f"{date}/{service}/tc3_request"
    string_to_sign = "\n".join(
        [
            "TC3-HMAC-SHA256",
            str(timestamp),
            credential_scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ]
    )
    secret_date = _hmac_sha256(("TC3" + TENCENT_SECRET_KEY).encode("utf-8"), date)
    secret_service = _hmac_sha256(secret_date, service)
    secret_signing = _hmac_sha256(secret_service, "tc3_request")
    signature = hmac.new(secret_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    authorization = (
        "TC3-HMAC-SHA256 "
        f"Credential={TENCENT_SECRET_ID}/{credential_scope}, "
        f"SignedHeaders={signed_headers}, Signature={signature}"
    )
    request = urllib.request.Request(
        f"https://{host}",
        data=payload,
        method="POST",
        headers={
            "Content-Type": content_type,
            "Host": host,
            "Authorization": authorization,
            "X-TC-Action": action,
            "X-TC-Version": version,
            "X-TC-Timestamp": str(timestamp),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"Tencent Cloud STT request failed: HTTP {exc.code} {detail}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Tencent Cloud STT network error: {exc.reason}") from exc

    response = result.get("Response", {})
    if response.get("Error"):
        error = response["Error"]
        raise RuntimeError(
            "Tencent Cloud STT failed: "
            f"{error.get('Code', 'Unknown')} {error.get('Message', '')}"
        )
    text = str(response.get("Result", "")).strip()
    if not text:
        raise ValueError("沒有識別到有效語音")
    print(
        "stt: Tencent Cloud succeeded "
        f"request_id={response.get('RequestId', 'unknown')} "
        f"audio_duration_ms={response.get('AudioDuration', 'unknown')}",
        flush=True,
    )
    return text


def transcribe_audio(audio: bytes) -> str:
    if STT_PROVIDER == "tencent":
        try:
            return transcribe_audio_with_tencent(audio)
        except ValueError:
            raise
        except Exception as exc:
            if STT_FALLBACK != "local":
                raise
            print(f"stt: Tencent Cloud failed ({exc}); falling back to local model", flush=True)
    return transcribe_audio_locally(audio)


def warm_stt() -> str:
    if STT_PROVIDER == "tencent":
        if not TENCENT_SECRET_ID or not TENCENT_SECRET_KEY:
            raise RuntimeError("Tencent Cloud STT credentials are not configured")
        return "tencent-configured"
    get_model()
    return "local-ready"


class Handler(BaseHTTPRequestHandler):
    server_version = "PetGateway/0.1"

    def log_message(self, fmt: str, *args: object) -> None:
        print(fmt % args, flush=True)

    def send_json(self, status: HTTPStatus, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def send_audio(self, data: bytes) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "audio/mpeg")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(HTTPStatus.OK, {"ok": True, "service": "pet-gateway"})
        else:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path not in ("/v1/chat", "/v1/audio", "/v1/tts", "/v1/warm"):
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        if not TOKEN or self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        try:
            started = time.monotonic()
            if self.path == "/v1/warm":
                with LOCK:
                    status = warm_stt()
                self.send_json(HTTPStatus.OK, {"ok": True, "stt": status, "provider": STT_PROVIDER})
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 5 * 1024 * 1024:
                raise ValueError("audio/text must be between 1 byte and 5 MB")
            body = self.rfile.read(length)
            if self.path == "/v1/audio":
                with LOCK:
                    stt_started = time.monotonic()
                    text = transcribe_audio(body)
                print(f"request: stt completed in {time.monotonic() - stt_started:.1f}s", flush=True)
            else:
                payload = json.loads(body)
                text = str(payload.get("text", "")).strip()
            if not text or len(text) > 500:
                raise ValueError("text must contain 1-500 characters")
            if self.path == "/v1/tts":
                audio = synthesize_speech(text)
                print(f"request: tts completed in {time.monotonic() - started:.1f}s", flush=True)
                self.send_audio(audio)
                return
            hermes_started = time.monotonic()
            answer = ask_hermes(text)
            print(f"request: hermes completed in {time.monotonic() - hermes_started:.1f}s; total {time.monotonic() - started:.1f}s", flush=True)
            self.send_json(HTTPStatus.OK, {"screen": answer, "speech": answer})
        except ValueError as exc:
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except subprocess.TimeoutExpired:
            self.send_json(HTTPStatus.GATEWAY_TIMEOUT, {"error": "Hermes 回應逾時"})
        except Exception as exc:
            print(f"gateway error: {exc}", flush=True)
            self.send_json(HTTPStatus.BAD_GATEWAY, {"error": "Hermes 暫時不可用"})


if __name__ == "__main__":
    if not TOKEN:
        raise SystemExit("PET_GATEWAY_TOKEN is required")
    ThreadingHTTPServer((HOST, 8787), Handler).serve_forever()

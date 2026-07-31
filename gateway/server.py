#!/usr/bin/env python3
"""Private Android-to-Hermes gateway for the desk-pet MVP."""

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import threading
import time
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
MAX_SCREEN_CHARS = 180
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


def transcribe_audio(audio: bytes) -> str:
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

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(HTTPStatus.OK, {"ok": True, "service": "pet-gateway"})
        else:
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:
        if self.path not in ("/v1/chat", "/v1/audio", "/v1/warm"):
            self.send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        if not TOKEN or self.headers.get("Authorization") != f"Bearer {TOKEN}":
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        try:
            started = time.monotonic()
            if self.path == "/v1/warm":
                with LOCK:
                    get_model()
                self.send_json(HTTPStatus.OK, {"ok": True, "stt": "ready"})
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

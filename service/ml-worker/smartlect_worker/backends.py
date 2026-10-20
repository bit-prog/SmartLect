"""
Модели за простыми интерфейсами: этапы не знают, что именно распознаёт речь или пишет текст.
Тяжёлые библиотеки импортируются лениво — процесс с одним этапом не тянет зависимости остальных.
"""

from __future__ import annotations

import io
import json
import logging
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Protocol

from .config import Config

log = logging.getLogger(__name__)

Progress = Callable[[float], None]
"""Сообщает долю выполненной работы этапа, 0.0–1.0."""


@dataclass(frozen=True)
class Segment:
    start_ms: int
    end_ms: int
    text: str


@dataclass(frozen=True)
class Transcript:
    language: str | None
    duration_ms: int
    segments: list[Segment]


class Transcriber(Protocol):
    def transcribe(self, audio: Path, progress: Progress) -> Transcript: ...


class BoardReader(Protocol):
    def read(self, image: bytes) -> str: ...


class LLM(Protocol):
    def complete(self, system: str, prompt: str) -> str: ...


# --- Настоящие модели ---------------------------------------------------------


class WhisperTranscriber:
    """faster-whisper (CTranslate2). Модель скачивается при первом запуске."""

    def __init__(self, config: Config):
        from faster_whisper import WhisperModel

        log.info("Loading Whisper model %s", config.whisper_model)
        self._model = WhisperModel(
            config.whisper_model, device=config.whisper_device, compute_type=config.whisper_compute_type)
        self._language = config.whisper_language

    def transcribe(self, audio: Path, progress: Progress) -> Transcript:
        segments, info = self._model.transcribe(str(audio), language=self._language, vad_filter=True)
        duration_s = info.duration or 1.0
        result = []
        # segments — ленивый генератор: распознавание идёт по мере чтения
        for s in segments:
            result.append(Segment(int(s.start * 1000), int(s.end * 1000), s.text.strip()))
            progress(min(s.end / duration_s, 1.0))
        return Transcript(info.language, int(duration_s * 1000), result)


class TesseractBoardReader:
    """Tesseract OCR. Рукописные формулы читает плохо — для них позже будет vision-модель (вариант B)."""

    def __init__(self, config: Config):
        import pytesseract  # noqa: F401 — проверяем наличие при старте

        self._lang = config.tesseract_lang

    def read(self, image: bytes) -> str:
        import pytesseract
        from PIL import Image, ImageOps

        img = ImageOps.autocontrast(ImageOps.grayscale(Image.open(io.BytesIO(image))))
        return pytesseract.image_to_string(img, lang=self._lang).strip()


class OllamaLLM:
    """Локальная LLM через HTTP API Ollama (/api/chat)."""

    def __init__(self, config: Config):
        self._url = config.ollama_url.rstrip("/") + "/api/chat"
        self._model = config.ollama_model
        self._num_ctx = config.ollama_num_ctx
        self._timeout = config.ollama_timeout_s

    def complete(self, system: str, prompt: str) -> str:
        body = {
            "model": self._model,
            "stream": False,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": prompt}],
            # num_ctx по умолчанию в Ollama маленький, кусок лекции в него не влезет
            "options": {"temperature": 0.3, "num_ctx": self._num_ctx},
        }
        request = urllib.request.Request(
            self._url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=self._timeout) as response:
            return json.loads(response.read())["message"]["content"].strip()


# --- Заглушки для WORKER_FAKE=1 -----------------------------------------------


class FakeTranscriber:
    def __init__(self, duration_ms: int | None):
        self._duration_ms = duration_ms

    def transcribe(self, audio: Path, progress: Progress) -> Transcript:
        duration = self._duration_ms or 10 * 60_000
        step = 30_000
        segments = []
        for i, start in enumerate(range(0, duration, step)):
            segments.append(Segment(start, min(start + step, duration), f"Фрагмент речи номер {i + 1}."))
            progress((start + step) / duration)
            time.sleep(0.01)
        return Transcript("ru", duration, segments)


class FakeBoardReader:
    def read(self, image: bytes) -> str:
        return f"Текст с доски ({len(image)} байт): E = mc^2"


class FakeLLM:
    def complete(self, system: str, prompt: str) -> str:
        if "ЗАГОЛОВОК" in prompt:
            return "Конспект лекции (тестовый режим)\nКраткое содержание, сгенерированное заглушкой."
        return "## Тема фрагмента\n\nКонспект, сгенерированный заглушкой.\n\n$$E = mc^2$$"

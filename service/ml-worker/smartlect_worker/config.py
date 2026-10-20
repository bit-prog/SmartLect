"""Настройки воркера из переменных окружения. Значения по умолчанию — для docker compose."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _flag(name: str) -> bool:
    return _env(name, "0").lower() in ("1", "true", "yes")


@dataclass(frozen=True)
class Config:
    rabbitmq_url: str = field(default_factory=lambda: _env(
        "RABBITMQ_URL", "amqp://smartlect:smartlect@localhost:5672/%2F"))

    s3_endpoint: str = field(default_factory=lambda: _env("S3_ENDPOINT", "http://localhost:9100"))
    s3_region: str = field(default_factory=lambda: _env("S3_REGION", "us-east-1"))
    s3_access_key: str = field(default_factory=lambda: _env("S3_ACCESS_KEY", "smartlect"))
    s3_secret_key: str = field(default_factory=lambda: _env("S3_SECRET_KEY", "smartlect-secret"))

    # Какие этапы обрабатывает этот процесс: "all" или через запятую "transcribe,board"
    stages: str = field(default_factory=lambda: _env("WORKER_STAGES", "all"))
    # Заглушки вместо моделей — прогнать конвейер без Whisper/Tesseract/Ollama
    fake: bool = field(default_factory=lambda: _flag("WORKER_FAKE"))

    whisper_model: str = field(default_factory=lambda: _env("WHISPER_MODEL", "small"))
    whisper_language: str | None = field(default_factory=lambda: os.environ.get("WHISPER_LANGUAGE") or None)
    whisper_device: str = field(default_factory=lambda: _env("WHISPER_DEVICE", "auto"))
    whisper_compute_type: str = field(default_factory=lambda: _env("WHISPER_COMPUTE_TYPE", "int8"))

    tesseract_lang: str = field(default_factory=lambda: _env("TESSERACT_LANG", "rus+eng"))

    ollama_url: str = field(default_factory=lambda: _env("OLLAMA_URL", "http://localhost:11434"))
    ollama_model: str = field(default_factory=lambda: _env("OLLAMA_MODEL", "qwen2.5:7b"))
    ollama_num_ctx: int = field(default_factory=lambda: int(_env("OLLAMA_NUM_CTX", "16384")))
    ollama_timeout_s: int = field(default_factory=lambda: int(_env("OLLAMA_TIMEOUT_S", "900")))

    # Длина куска лекции, по которому LLM пишет один раздел конспекта
    chunk_minutes: int = field(default_factory=lambda: int(_env("CHUNK_MINUTES", "12")))

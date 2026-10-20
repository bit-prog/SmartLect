"""Формат сообщений с оркестратором (см. processing/Messages.scala)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

EXCHANGE = "smartlect"
EVENTS_ROUTING_KEY = "lecture.events"


@dataclass(frozen=True)
class Photo:
    id: str
    key: str
    offset_ms: int


@dataclass(frozen=True)
class Task:
    lecture_id: str
    stage: str
    attempt: int
    bucket: str
    audio_key: str
    duration_ms: int | None
    photos: list[Photo]
    transcript_key: str
    board_key: str
    sections_key: str
    notes_key: str

    @staticmethod
    def parse(body: bytes) -> Task:
        d = json.loads(body)
        return Task(
            lecture_id=d["lectureId"],
            stage=d["stage"],
            attempt=int(d["attempt"]),
            bucket=d["bucket"],
            audio_key=d["audioKey"],
            duration_ms=d.get("durationMs"),
            photos=[Photo(p["id"], p["key"], int(p["offsetMs"])) for p in d.get("photos", [])],
            transcript_key=d["transcriptKey"],
            board_key=d["boardKey"],
            sections_key=d["sectionsKey"],
            notes_key=d["notesKey"],
        )


def event(task: Task, kind: str, **fields: Any) -> bytes:
    """Событие для оркестратора; kind — PROGRESS, COMPLETED или FAILED. None-поля не отправляются."""
    body = {"lectureId": task.lecture_id, "stage": task.stage, "attempt": task.attempt, "type": kind}
    body.update({k: v for k, v in fields.items() if v is not None})
    return json.dumps(body, ensure_ascii=False).encode()

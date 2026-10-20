"""TRANSCRIBING: аудио → transcript.json с сегментами и таймкодами."""

from __future__ import annotations

import tempfile
from pathlib import Path

from ..backends import FakeTranscriber, WhisperTranscriber
from ..messages import Task
from .common import Context, Progress


def run(task: Task, ctx: Context, progress: Progress) -> dict:
    transcriber = (
        FakeTranscriber(task.duration_ms) if ctx.config.fake
        else ctx.get("whisper", lambda: WhisperTranscriber(ctx.config))
    )
    with tempfile.TemporaryDirectory() as tmp:
        audio = Path(tmp) / "audio"
        ctx.storage.download(task.bucket, task.audio_key, audio)
        transcript = transcriber.transcribe(audio, progress)

    ctx.storage.write_json(task.bucket, task.transcript_key, {
        "language": transcript.language,
        "durationMs": transcript.duration_ms,
        "segments": [{"startMs": s.start_ms, "endMs": s.end_ms, "text": s.text} for s in transcript.segments],
    })
    return {"durationMs": transcript.duration_ms}

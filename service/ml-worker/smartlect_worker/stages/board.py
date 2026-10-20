"""READING_BOARD: фото доски → board.json с распознанным текстом для каждого фото."""

from __future__ import annotations

from ..backends import FakeBoardReader, TesseractBoardReader
from ..messages import Task
from .common import Context, Progress


def run(task: Task, ctx: Context, progress: Progress) -> dict:
    reader = (
        FakeBoardReader() if ctx.config.fake
        else ctx.get("tesseract", lambda: TesseractBoardReader(ctx.config))
    )
    photos = []
    for i, photo in enumerate(task.photos):
        text = reader.read(ctx.storage.read_bytes(task.bucket, photo.key))
        photos.append({"id": photo.id, "offsetMs": photo.offset_ms, "text": text})
        progress((i + 1) / len(task.photos))

    ctx.storage.write_json(task.bucket, task.board_key, {"photos": photos})
    return {}

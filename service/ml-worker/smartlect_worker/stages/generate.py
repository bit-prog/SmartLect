"""
GENERATING: расшифровка + текст доски → sections.json.

Лекция режется на куски по CHUNK_MINUTES: 1,5 часа целиком не влезают в контекст локальной модели.
К каждому куску прикладываются фото, сделанные в его интервале, и LLM пишет по нему раздел.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .. import prompts
from ..backends import FakeLLM, OllamaLLM
from ..messages import Task
from .common import Context, Progress, fmt_time


@dataclass
class Chunk:
    start_ms: int
    end_ms: int
    texts: list[str] = field(default_factory=list)
    board: list[tuple[int, str]] = field(default_factory=list)


def build_chunks(segments: list[dict], photos: list[dict], chunk_ms: int) -> list[Chunk]:
    """Режет сегменты на куски не длиннее chunk_ms (по границам сегментов) и раскладывает по ним фото."""
    chunks: list[Chunk] = []
    for seg in segments:
        if not chunks or seg["endMs"] - chunks[-1].start_ms > chunk_ms:
            chunks.append(Chunk(seg["startMs"], seg["endMs"]))
        chunks[-1].end_ms = seg["endMs"]
        chunks[-1].texts.append(seg["text"])

    for photo in sorted(photos, key=lambda p: p["offsetMs"]):
        if not photo["text"]:
            continue
        if not chunks:
            chunks.append(Chunk(photo["offsetMs"], photo["offsetMs"]))
        # Фото — в кусок, внутри которого оно сделано; до первого / после последнего — в крайний
        target = next((c for c in chunks if photo["offsetMs"] < c.end_ms), chunks[-1])
        target.board.append((photo["offsetMs"], photo["text"]))
    return chunks


def headings(markdown: str) -> list[str]:
    return [line[3:].strip() for line in markdown.splitlines() if line.startswith("## ")]


def run(task: Task, ctx: Context, progress: Progress) -> dict:
    llm = FakeLLM() if ctx.config.fake else ctx.get("ollama", lambda: OllamaLLM(ctx.config))
    transcript = ctx.storage.read_json(task.bucket, task.transcript_key)
    board = ctx.storage.read_json(task.bucket, task.board_key)
    chunks = build_chunks(transcript["segments"], board["photos"], ctx.config.chunk_minutes * 60_000)

    sections = []
    previous: list[str] = []
    for i, chunk in enumerate(chunks):
        prompt = prompts.SECTION_PROMPT.format(
            start=fmt_time(chunk.start_ms),
            end=fmt_time(chunk.end_ms),
            # Чтобы разделы не повторяли друг друга
            previous=f"Уже написанные разделы: {'; '.join(previous)}.\n" if previous else "",
            transcript=" ".join(chunk.texts) or "(речи нет)",
            board="\n".join(f"[{fmt_time(t)}] {text}" for t, text in chunk.board) or "(нет)",
        )
        markdown = llm.complete(prompts.SECTION_SYSTEM, prompt)
        sections.append({"index": i, "startMs": chunk.start_ms, "endMs": chunk.end_ms, "markdown": markdown})
        previous += headings(markdown)
        progress((i + 1) / len(chunks))

    ctx.storage.write_json(task.bucket, task.sections_key, {"sections": sections})
    return {}

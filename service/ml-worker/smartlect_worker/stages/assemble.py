"""
ASSEMBLING: sections.json → notes.md.

LLM здесь пишет только заголовок и краткое содержание по заголовкам разделов, а оглавление
и склейка делаются кодом: весь конспект за раз в контекст локальной модели не помещается.
"""

from __future__ import annotations

import re

from .. import prompts
from ..backends import LLM, FakeLLM, OllamaLLM
from ..messages import Task
from .common import Context, Progress, fmt_time
from .generate import headings


def slug(heading: str) -> str:
    """Якорь заголовка в стиле GitHub: строчные буквы, пробелы → дефисы, без пунктуации."""
    text = re.sub(r"[^\w\- ]", "", heading.lower(), flags=re.UNICODE)
    return text.strip().replace(" ", "-")


# Модели любят повторять разметку из промпта: «1) ЗАГОЛОВОК: …», «2) Краткое содержание — …»
_LABEL = re.compile(r"^\s*(?:\d+[).]\s*)?(?:(?:заголовок(?: лекции)?|краткое содержание)\s*(?:[:—-]\s*|\s+))?", re.IGNORECASE)


def _clean(line: str) -> str:
    return _LABEL.sub("", line).strip(" «»\"#*")


def title_and_summary(llm: LLM, all_headings: list[str]) -> tuple[str, str]:
    answer = llm.complete(prompts.TITLE_SYSTEM, prompts.TITLE_PROMPT.format(
        headings="\n".join(f"- {h}" for h in all_headings) or "- (нет разделов)"))
    lines = [_clean(line) for line in answer.splitlines() if _clean(line)]
    title = lines[0] if lines else "Конспект лекции"
    summary = " ".join(lines[1:])
    return title, summary


def assemble(sections: list[dict], title: str, summary: str) -> str:
    parts = [f"# {title}"]
    if summary:
        parts.append(f"> {summary}")

    all_headings = [(h, s["startMs"]) for s in sections for h in headings(s["markdown"])]
    if len(all_headings) > 1:
        # Как в GitHub: повторяющийся якорь получает суффикс -1, -2, …
        seen: dict[str, int] = {}
        lines = []
        for h, t in all_headings:
            anchor = slug(h)
            n = seen.get(anchor, 0)
            seen[anchor] = n + 1
            lines.append(f"- [{h}](#{anchor}{f'-{n}' if n else ''}) · {fmt_time(t)}")
        parts.append("## Содержание\n\n" + "\n".join(lines))

    parts += [s["markdown"].strip() for s in sections]
    return "\n\n".join(parts) + "\n"


def run(task: Task, ctx: Context, progress: Progress) -> dict:
    llm = FakeLLM() if ctx.config.fake else ctx.get("ollama", lambda: OllamaLLM(ctx.config))
    sections = ctx.storage.read_json(task.bucket, task.sections_key)["sections"]
    title, summary = title_and_summary(llm, [h for s in sections for h in headings(s["markdown"])])
    progress(0.5)
    ctx.storage.write_text(task.bucket, task.notes_key, assemble(sections, title, summary), "text/markdown")
    return {}

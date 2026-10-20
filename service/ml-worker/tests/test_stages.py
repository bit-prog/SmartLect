from smartlect_worker.backends import FakeLLM
from smartlect_worker.messages import Task, event
from smartlect_worker.runner import ProgressReporter, parse_stages
from smartlect_worker.stages.assemble import assemble, slug, title_and_summary
from smartlect_worker.stages.common import fmt_time
from smartlect_worker.stages.generate import build_chunks

MIN = 60_000


def seg(start_min: float, end_min: float, text: str = "текст") -> dict:
    return {"startMs": int(start_min * MIN), "endMs": int(end_min * MIN), "text": text}


def test_chunks_split_by_length_and_photos_go_to_their_interval():
    segments = [seg(0, 5), seg(5, 10), seg(10, 15), seg(15, 20), seg(20, 25)]
    photos = [
        {"offsetMs": 12 * MIN, "text": "формула 2"},
        {"offsetMs": 1 * MIN, "text": "формула 1"},
        {"offsetMs": 40 * MIN, "text": "после конца"},
        {"offsetMs": 3 * MIN, "text": ""},  # пустой OCR не попадает в промпт
    ]
    chunks = build_chunks(segments, photos, chunk_ms=12 * MIN)

    assert [(c.start_ms, c.end_ms) for c in chunks] == [(0, 10 * MIN), (10 * MIN, 20 * MIN), (20 * MIN, 25 * MIN)]
    assert [t for _, t in chunks[0].board] == ["формула 1"]
    assert [t for _, t in chunks[1].board] == ["формула 2"]
    assert [t for _, t in chunks[2].board] == ["после конца"]


def test_chunks_for_board_only_lecture():
    chunks = build_chunks([], [{"offsetMs": 0, "text": "доска"}], chunk_ms=12 * MIN)
    assert len(chunks) == 1 and chunks[0].board == [(0, "доска")]


def test_assemble_builds_title_and_toc():
    sections = [
        {"startMs": 0, "markdown": "## Пределы\n\nТекст"},
        {"startMs": 12 * MIN, "markdown": "## Производная, определение\n\n$f'(x)$"},
    ]
    notes = assemble(sections, "Матанализ", "Пределы и производные.")
    assert notes.startswith("# Матанализ\n\n> Пределы и производные.\n\n## Содержание")
    assert "- [Пределы](#пределы) · 00:00" in notes
    assert "- [Производная, определение](#производная-определение) · 12:00" in notes
    assert notes.endswith("$f'(x)$\n")


def test_title_parsing_strips_numbering():
    class LLM:
        def complete(self, system, prompt):
            return "1) ЗАГОЛОВОК: «Ряды Фурье»\n2) Разложение функций в ряд."

    assert title_and_summary(LLM(), ["Ряды"]) == ("Ряды Фурье", "Разложение функций в ряд.")
    assert title_and_summary(FakeLLM(), []) == (
        "Конспект лекции (тестовый режим)", "Краткое содержание, сгенерированное заглушкой.")


def test_slug_and_time_format():
    assert slug("Теорема Коши (доказательство)") == "теорема-коши-доказательство"
    assert fmt_time(65_000) == "01:05"
    assert fmt_time(3_725_000) == "1:02:05"


def test_progress_reporter_throttles_and_never_reports_100():
    sent = []
    reporter = ProgressReporter(sent.append, interval_s=0)
    for f in (0.1, 0.1, 0.5, 0.3, 1.0):
        reporter(f)
    assert sent == [10, 50, 99]


def test_task_parsing_and_event_format():
    task = Task.parse(b'{"lectureId":"l1","stage":"GENERATING","attempt":2,"bucket":"b","audioKey":"a",'
                      b'"durationMs":null,"photos":[{"id":"p","key":"k","offsetMs":5}],'
                      b'"transcriptKey":"t","boardKey":"bo","sectionsKey":"s","notesKey":"n"}')
    assert task.photos[0].offset_ms == 5 and task.duration_ms is None
    assert event(task, "COMPLETED", durationMs=None) == \
        b'{"lectureId": "l1", "stage": "GENERATING", "attempt": 2, "type": "COMPLETED"}'


def test_parse_stages():
    assert parse_stages("all") == ["TRANSCRIBING", "READING_BOARD", "GENERATING", "ASSEMBLING"]
    assert parse_stages("transcribe, board") == ["TRANSCRIBING", "READING_BOARD"]


def test_duplicate_headings_get_unique_anchors():
    sections = [{"startMs": 0, "markdown": "## Примеры"}, {"startMs": MIN, "markdown": "## Примеры"}]
    notes = assemble(sections, "T", "")
    assert "(#примеры) · 00:00" in notes and "(#примеры-1) · 01:00" in notes

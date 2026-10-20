from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..backends import Progress
from ..config import Config
from ..storage import Storage


@dataclass
class Context:
    config: Config
    storage: Storage
    # Модели создаются при первом использовании и переиспользуются между задачами
    cache: dict[str, Any] = field(default_factory=dict)

    def get(self, name: str, factory):
        if name not in self.cache:
            self.cache[name] = factory()
        return self.cache[name]


def fmt_time(ms: int) -> str:
    total = ms // 1000
    h, m, s = total // 3600, total % 3600 // 60, total % 60
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


__all__ = ["Context", "Progress", "fmt_time"]

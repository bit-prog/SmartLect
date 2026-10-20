"""Запуск: python -m smartlect_worker (настройки — переменные окружения, см. config.py)."""

import logging

from .config import Config
from .runner import Worker, parse_stages
from .stages.common import Context
from .storage import Storage


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    logging.getLogger("pika").setLevel(logging.WARNING)
    config = Config()
    if config.fake:
        logging.warning("WORKER_FAKE=1: models are replaced with stubs")
    Worker(Context(config, Storage(config)), parse_stages(config.stages)).run()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

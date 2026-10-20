"""Доступ к S3 (SeaweedFS локально)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import boto3
from botocore.config import Config as BotoConfig

from .config import Config


class Storage:
    def __init__(self, config: Config):
        self._s3 = boto3.client(
            "s3",
            endpoint_url=config.s3_endpoint,
            region_name=config.s3_region,
            aws_access_key_id=config.s3_access_key,
            aws_secret_access_key=config.s3_secret_key,
            config=BotoConfig(
                # S3-совместимые хранилища: бакет в пути, без новых контрольных сумм AWS
                s3={"addressing_style": "path"},
                request_checksum_calculation="when_required",
                response_checksum_validation="when_required",
            ),
        )

    def download(self, bucket: str, key: str, path: Path) -> None:
        self._s3.download_file(bucket, key, str(path))

    def read_bytes(self, bucket: str, key: str) -> bytes:
        return self._s3.get_object(Bucket=bucket, Key=key)["Body"].read()

    def read_json(self, bucket: str, key: str) -> Any:
        return json.loads(self.read_bytes(bucket, key))

    def write_json(self, bucket: str, key: str, data: Any) -> None:
        body = json.dumps(data, ensure_ascii=False, indent=2).encode()
        self._s3.put_object(Bucket=bucket, Key=key, Body=body, ContentType="application/json")

    def write_text(self, bucket: str, key: str, text: str, content_type: str) -> None:
        self._s3.put_object(Bucket=bucket, Key=key, Body=text.encode(), ContentType=f"{content_type}; charset=utf-8")

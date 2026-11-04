"""Noi luu tru tep cho admin-web: dia cuc bo (mac dinh) hoac SeaweedFS/S3 (khi dat FTP_S3_ENDPOINT).

Cung mot "rel" (duong dan tuong doi tu goc, vd "sv/bao_cao.pdf") nhu bang files. Cach ly tep: local -> thu muc QUARANTINE,
S3 -> bucket rieng (khong thuoc cay thu muc FTP nen nguoi dung khong thay).
"""
from __future__ import annotations

import hashlib
import os
import shutil
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator


class StorageError(Exception):
    pass


def _s3err(fn):
    """Doi loi botocore (tep bi xoa giua chung, mang...) thanh StorageError de giao dien bao loi ro thay vi 500."""
    import functools

    @functools.wraps(fn)
    def wrapper(*a, **kw):
        try:
            return fn(*a, **kw)
        except StorageError:
            raise
        except Exception as e:
            code = getattr(e, "response", {}).get("Error", {}).get("Code")
            if code in ("404", "NoSuchKey", "NotFound"):
                raise StorageError("Tep khong con trong kho luu tru (da bi xoa hoac doi ten) - hay Dong bo lai") from e
            if code is not None or e.__class__.__module__.startswith(("botocore", "boto3")):
                raise StorageError(f"Loi kho luu tru: {code or e}") from e
            raise
    return wrapper


def _hash_stream(chunks) -> str:
    h = hashlib.sha256()
    for chunk in chunks:
        h.update(chunk)
    return h.hexdigest()


class LocalStorage:
    kind = "local"

    def __init__(self, root: Path, quarantine: Path):
        self.root = root
        self.quarantine_dir = quarantine

    def _abs(self, rel: str) -> Path:
        p = (self.root / rel.lstrip("/")).resolve()
        if self.root not in p.parents:
            raise StorageError("Duong dan ngoai root")
        return p

    def iter_files(self) -> Iterator[tuple[str, int, int]]:
        for p in self.root.rglob("*"):
            if not p.is_file() or ".part-" in p.name:
                continue
            st = p.stat()
            yield p.relative_to(self.root).as_posix(), st.st_size, int(st.st_mtime)

    def sha256(self, rel: str) -> str:
        with open(self._abs(rel), "rb") as f:
            return _hash_stream(iter(lambda: f.read(1 << 20), b""))

    def exists(self, rel: str) -> bool:
        return self._abs(rel).exists()

    @contextmanager
    def local_copy(self, rel: str):
        yield self._abs(rel)

    def quarantine(self, rel: str, file_id: int) -> str:
        src = self._abs(rel)
        self.quarantine_dir.mkdir(parents=True, exist_ok=True)
        dest = self.quarantine_dir / f"{file_id}_{src.name}"
        shutil.move(str(src), dest)
        return str(dest)

    def restore(self, rel: str, qpath: str) -> None:
        dest = self._abs(rel)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(qpath, dest)

    def purge(self, qpath: str) -> None:
        Path(qpath).unlink(missing_ok=True)


class S3Storage:
    kind = "s3"

    def __init__(self, endpoint: str, access_key: str, secret_key: str, bucket: str, prefix: str = "",
                 region: str = "us-east-1", quarantine_bucket: str | None = None, client=None):
        if client is None:
            import boto3
            from botocore.config import Config
            client = boto3.client(
                "s3", endpoint_url=endpoint, aws_access_key_id=access_key, aws_secret_access_key=secret_key,
                region_name=region,
                config=Config(s3={"addressing_style": "path"}, signature_version="s3v4",
                              request_checksum_calculation="when_required",
                              response_checksum_validation="when_required"))
        self.s3 = client
        self.bucket = bucket
        p = prefix.strip("/")
        self.prefix = p + "/" if p else ""
        self.qbucket = quarantine_bucket or f"{bucket}-quarantine"
        self._ensure_bucket(self.bucket)
        self._ensure_bucket(self.qbucket)

    def _ensure_bucket(self, name: str) -> None:
        try:
            self.s3.head_bucket(Bucket=name)
        except Exception:
            self.s3.create_bucket(Bucket=name)

    def _key(self, rel: str) -> str:
        parts = [x for x in rel.replace("\\", "/").split("/") if x not in ("", ".")]
        if ".." in parts:
            raise StorageError("Duong dan ngoai root")
        return self.prefix + "/".join(parts)

    def iter_files(self) -> Iterator[tuple[str, int, int]]:
        for page in self.s3.get_paginator("list_objects_v2").paginate(Bucket=self.bucket, Prefix=self.prefix):
            for o in page.get("Contents", []):
                key = o["Key"]
                if key.endswith("/") or ".part-" in key.rsplit("/", 1)[-1]:
                    continue  # marker thu muc / tep tam
                yield key[len(self.prefix):], o["Size"], int(o["LastModified"].timestamp())

    @_s3err
    def sha256(self, rel: str) -> str:
        body = self.s3.get_object(Bucket=self.bucket, Key=self._key(rel))["Body"]
        try:
            return _hash_stream(body.iter_chunks(1 << 20))
        finally:
            body.close()

    def _head(self, bucket: str, key: str) -> bool:
        try:
            self.s3.head_object(Bucket=bucket, Key=key)
            return True
        except Exception as e:  # botocore ClientError 404
            if getattr(e, "response", {}).get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
                return False
            raise

    def exists(self, rel: str) -> bool:
        return self._head(self.bucket, self._key(rel))

    @contextmanager
    def local_copy(self, rel: str):
        """Tai ban sao tam de quet (clamscan can duong dan tep), xoa ngay sau khi dung."""
        fd, name = tempfile.mkstemp(prefix="ftpadmin-scan-")
        os.close(fd)
        try:
            try:
                self.s3.download_file(self.bucket, self._key(rel), name)
            except Exception as e:
                raise StorageError(f"Khong tai duoc tep de quet: {e}") from e
            yield Path(name)
        finally:
            Path(name).unlink(missing_ok=True)

    @_s3err
    def quarantine(self, rel: str, file_id: int) -> str:
        key = self._key(rel)
        qkey = f"{file_id}_{key.rsplit('/', 1)[-1]}"
        self.s3.copy_object(Bucket=self.qbucket, Key=qkey, CopySource={"Bucket": self.bucket, "Key": key})
        try:
            self.s3.delete_object(Bucket=self.bucket, Key=key)
        except Exception:
            self.s3.delete_object(Bucket=self.qbucket, Key=qkey)  # khong de tep nam o hai noi
            raise
        return f"s3://{self.qbucket}/{qkey}"

    def _parse_q(self, qpath: str) -> tuple[str, str]:
        if not qpath.startswith("s3://"):
            raise StorageError(f"quarantine_path khong phai S3: {qpath}")
        bucket, _, key = qpath[5:].partition("/")
        return bucket, key

    @_s3err
    def restore(self, rel: str, qpath: str) -> None:
        qb, qk = self._parse_q(qpath)
        self.s3.copy_object(Bucket=self.bucket, Key=self._key(rel), CopySource={"Bucket": qb, "Key": qk})
        try:
            self.s3.delete_object(Bucket=qb, Key=qk)
        except Exception:
            self.s3.delete_object(Bucket=self.bucket, Key=self._key(rel))
            raise

    @_s3err
    def purge(self, qpath: str) -> None:
        qb, qk = self._parse_q(qpath)
        self.s3.delete_object(Bucket=qb, Key=qk)


def from_env(root: Path, quarantine: Path):
    endpoint = os.environ.get("FTP_S3_ENDPOINT", "").strip()
    if not endpoint:
        return LocalStorage(root, quarantine)
    try:
        return S3Storage(
            endpoint, os.environ["FTP_S3_ACCESS_KEY"], os.environ["FTP_S3_SECRET_KEY"],
            os.environ.get("FTP_S3_BUCKET", "ftp-files"), os.environ.get("FTP_S3_PREFIX", ""),
            os.environ.get("FTP_S3_REGION", "us-east-1"), os.environ.get("FTP_S3_QUARANTINE_BUCKET") or None)
    except KeyError as e:
        raise SystemExit(f"FTP_S3_ENDPOINT da dat nhung thieu bien moi truong {e}")

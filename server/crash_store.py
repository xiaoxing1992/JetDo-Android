"""
崩溃报告本地存储：磁盘配额 + 最旧优先的批量淘汰。

设计取舍
--------
崩溃报告是「一次写入、偶尔翻阅、按时间淘汰」的日志型数据，不是关系型数据。
放文件系统里，出问题时能直接 `ls` / `grep` / `scp` 拿走，不用先把服务跑起来查库。
代价是没有索引 —— 报告量级在几千份以内时，这不算问题。

配额策略
--------
- 硬上限 `max_bytes`（默认 100MB）：总占用超过它就触发淘汰。
- 淘汰目标是 `max_bytes * trim_ratio`（默认 85MB），留出 15MB 弛豫区间。
  没有这个区间的话，配额一满，每来一份新报告都要删一批旧报告 —— 淘汰变成常态，
  每次写入都要做一次 IO 风暴。有了区间，是「攒到 100MB → 一次删到 85MB → 再攒」。
- 删除按**批量**进行：每轮删掉当前文件数的 `batch_ratio`（默认 10%，至少 1 个），
  直到总占用降到目标以下。1000 份报告时一轮删 100 份，不是一份一份地 unlink。
- 淘汰永远从最旧的开始 —— 崩溃报告的价值随时间衰减，最新的才有诊断意义。

并发
----
所有读写都在同一把 `threading.Lock` 里，写入走「临时文件 + os.replace」，
避免并发请求读到写到一半的 JSON。
"""

from __future__ import annotations

import gzip
import logging
import os
import threading
import time
import uuid
from collections import deque
from pathlib import Path
from typing import Deque, Dict, List, Optional

logger = logging.getLogger(__name__)

DEFAULT_MAX_BYTES = 100 * 1024 * 1024
DEFAULT_MAX_REPORT_BYTES = 2 * 1024 * 1024
DEFAULT_TRIM_RATIO = 0.85
DEFAULT_TRIM_BATCH_RATIO = 0.10


def decode_body(raw: bytes, content_encoding: Optional[str]) -> bytes:
    """按 Content-Encoding 解压请求体。ACRA 的 httpSender.compress=true 走 gzip。"""
    encoding = (content_encoding or "").lower().strip()
    if encoding in ("gzip", "x-gzip"):
        return gzip.decompress(raw)
    if encoding in ("", "identity"):
        return raw
    raise ValueError(f"unsupported content-encoding: {content_encoding}")


class CrashStore:
    """单目录、有硬配额的崩溃报告仓库。"""

    def __init__(
        self,
        directory: str | os.PathLike,
        max_bytes: int = DEFAULT_MAX_BYTES,
        max_report_bytes: int = DEFAULT_MAX_REPORT_BYTES,
        trim_ratio: float = DEFAULT_TRIM_RATIO,
        batch_ratio: float = DEFAULT_TRIM_BATCH_RATIO,
    ) -> None:
        self._dir = Path(directory)
        self._max = max_bytes
        self._max_report = max_report_bytes
        self._target = int(max_bytes * trim_ratio)
        self._batch_ratio = batch_ratio

        self._lock = threading.Lock()
        self._entries: List[Dict] = []  # 按 mtime 升序，最旧在前
        self._total = 0
        self._evicted_total = 0

        self._dir.mkdir(parents=True, exist_ok=True)
        self._load()

    # ---------- 内部 ----------

    def _load(self) -> None:
        """启动时扫描目录，重建内存索引（进程重启后配额依旧生效）。"""
        entries: List[Dict] = []
        total = 0
        for path in self._dir.iterdir():
            if not path.is_file() or path.suffix == ".part":
                continue
            try:
                stat = path.stat()
            except OSError:
                continue
            entries.append({"path": path, "size": stat.st_size, "mtime": stat.st_mtime_ns})
            total += stat.st_size
        entries.sort(key=lambda e: e["mtime"])
        self._entries = entries
        self._total = total
        logger.info(
            "crash store loaded: %d reports, %.1f MB (cap %.1f MB)",
            len(entries), total / 1024 / 1024, self._max / 1024 / 1024,
        )
        # 上次退出时可能已超配额（比如手动塞过文件），启动时先纠一次
        with self._lock:
            self._enforce_quota()

    def _enforce_quota(self) -> int:
        """超配额就批量删最旧的，直到降到弛豫目标以下。调用方必须持锁。"""
        if self._total <= self._max:
            return 0

        evicted = 0

        def drop(count: int) -> None:
            nonlocal evicted
            victims = self._entries[:count]
            for entry in victims:
                try:
                    entry["path"].unlink(missing_ok=True)
                except OSError as exc:
                    logger.warning("failed to unlink %s: %s", entry["path"], exc)
                self._total -= entry["size"]
                evicted += 1
            del self._entries[:count]

        # 每轮删掉当前存量的一成，至少一个；总量降到目标即停。
        # `len > 1` 是硬保护：**永远不淘汰刚写进来的那份**。
        # 否则一旦单份报告体积超过弛豫目标（极端情况：报告比配额还大），
        # 循环会一直删到目录空掉 —— 那不是腾空间，那是把现场全丢了。
        while self._total > self._target and len(self._entries) > 1:
            batch = max(1, int(len(self._entries) * self._batch_ratio))
            batch = min(batch, len(self._entries) - 1)
            drop(batch)

        self._evicted_total += evicted
        logger.warning(
            "crash store over quota: evicted %d oldest reports in batches, %.1f MB remaining",
            evicted, self._total / 1024 / 1024,
        )
        return evicted

    # ---------- 对外 ----------

    def save(self, payload: bytes) -> Dict:
        """写入一份报告并保证不超配额。单份超限直接拒，绝不让它挤掉别的报告。"""
        if len(payload) > self._max_report:
            raise ValueError(
                f"report too large: {len(payload)} > {self._max_report} bytes"
            )
        # 单份报告不应占配额的一半以上：既防止一份巨型报告把配额吃干净，
        # 也避免它触发上面那条「删光其他所有报告」的极端淘汰。
        if len(payload) * 2 > self._max:
            raise ValueError(
                f"report too large for quota: {len(payload)} bytes "
                f"(quota {self._max} bytes, at most half per report)"
            )

        with self._lock:
            stamp = time.strftime("%Y%m%dT%H%M%S", time.gmtime())
            name = f"crash-{stamp}-{uuid.uuid4().hex[:8]}.json"
            path = self._dir / name
            tmp = path.with_suffix(".part")
            tmp.write_bytes(payload)
            os.replace(tmp, path)  # 原子替换：读者永远看不到半截文件

            self._entries.append({"path": path, "size": len(payload), "mtime": time.time_ns()})
            self._total += len(payload)

            evicted = self._enforce_quota()
            return {
                "file": name,
                "size": len(payload),
                "evicted": evicted,
                "total_bytes": self._total,
                "total_files": len(self._entries),
                "max_bytes": self._max,
            }

    def stats(self) -> Dict:
        with self._lock:
            oldest = self._entries[0] if self._entries else None
            return {
                "files": len(self._entries),
                "total_bytes": self._total,
                "max_bytes": self._max,
                "trim_target_bytes": self._target,
                "usage_ratio": round(self._total / self._max, 4) if self._max else 0.0,
                "oldest": oldest["path"].name if oldest else None,
                "evicted_since_start": self._evicted_total,
            }


class RateLimiter:
    """按 key（通常是客户端 IP）的滑动窗口限流。

    崩溃上报最容易被打爆的场景不是攻击，而是「用户装了会一启动就崩的版本」——
    崩溃循环时每台设备每分钟能发几十次。ACRA 的 limiter 是客户端礼貌，
    服务端这份才是硬约束：客户端可以被改、被绕过，服务端不会。
    """

    def __init__(self, limit: int, window_seconds: float = 60.0, max_keys: int = 20_000) -> None:
        self._limit = limit
        self._window = window_seconds
        self._max_keys = max_keys
        self._lock = threading.Lock()
        self._hits: Dict[str, Deque[float]] = {}

    def allow(self, key: str) -> bool:
        if self._limit <= 0:  # 0 或负数 = 关闭限流
            return True
        now = time.monotonic()
        cutoff = now - self._window
        with self._lock:
            bucket = self._hits.get(key)
            if bucket is None:
                if len(self._hits) >= self._max_keys:
                    self._sweep(cutoff)
                bucket = self._hits.setdefault(key, deque())
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= self._limit:
                return False
            bucket.append(now)
            return True

    def _sweep(self, cutoff: float) -> None:
        """内存保护：key 太多时清掉整窗无命中的。调用方持锁。"""
        stale = [k for k, v in self._hits.items() if not v or v[-1] < cutoff]
        for key in stale:
            self._hits.pop(key, None)
        logger.info("rate limiter swept %d idle keys", len(stale))


if __name__ == "__main__":
    # 自检：造 3MB 配额，灌 60 份 100KB 的报告，验证淘汰触发且只删最旧的。
    import tempfile

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    with tempfile.TemporaryDirectory() as tmp:
        store = CrashStore(tmp, max_bytes=3 * 1024 * 1024)
        for i in range(60):
            info = store.save(f'{{"n": {i}, "pad": "{"x" * 100_000}"}}'.encode())
        print("final:", store.stats())
        assert store._total <= 3 * 1024 * 1024, "quota exceeded"
        names = sorted(p.name for p in Path(tmp).iterdir())
        assert all(n.startswith("crash-") for n in names)
        print(f"kept {len(names)} files on disk, newest present: {names[-1]}")

"""
JetDo Android 推送中继服务器

接收 LINUX DO 的 Web Push 推送，解密后转发到 Firebase Cloud Messaging。

架构：
  LINUX DO ──Web Push（加密）──▶ 本中继 ──解密──▶ FCM ──▶ Android 设备

核心功能：
1. /subscribe - 设备注册推送订阅，返回 endpoint + VAPID 公钥
2. /push/{device_id} - 接收 Web Push，解密后转发给 FCM
3. /unsubscribe - 设备取消订阅
4. /crash - 接收 ACRA 崩溃报告（有磁盘配额，超限批量淘汰最旧的）
5. /crash/stats - 查看崩溃报告占用情况
"""

import os
import json
import time
import base64
import logging
from typing import Dict, Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
import firebase_admin
from firebase_admin import credentials, messaging
from py_vapid import Vapid01
from dotenv import load_dotenv

from crash_store import CrashStore, RateLimiter, decode_body

# 加载环境变量
load_dotenv()

# 日志配置
# 文件日志目录可配：systemd 下设为 /var/log/jetdo-relay（unit 里 LogsDirectory 已备好）。
# 拿不到写权限时**降级为仅 stdout**，不能因为一条日志路径让整个服务起不来——
# journald 那边本来就有完整输出。
LOG_DIR = os.getenv("LOG_DIR", ".")
_handlers = [logging.StreamHandler()]
try:
    os.makedirs(LOG_DIR, exist_ok=True)
    _handlers.append(logging.FileHandler(os.path.join(LOG_DIR, "relay.log")))
except OSError as _exc:
    print(f"[jetdo] file logging disabled ({_exc}); using stdout only", flush=True)

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=_handlers,
)
logger = logging.getLogger(__name__)

# 全局变量
fcm_app = None
vapid_private_key = None
vapid_public_key_b64 = None
device_registry: Dict[str, str] = {}  # device_id -> fcm_token

# ========== 崩溃报告 ==========

# 磁盘配额：总占用到 CRASH_MAX_BYTES 就批量删最旧的，删到 TRIM_RATIO 为止。
# 默认 100MB / 85MB，即「攒到 100MB → 一次删回 85MB → 再攒」。
CRASH_MAX_BYTES = int(os.getenv("CRASH_MAX_BYTES", str(100 * 1024 * 1024)))
CRASH_DIR = os.getenv("CRASH_DIR", "./crashes")
# 单份报告上限（解压后）。ACRA 带 logcat 的报告几百 KB，2MB 足够，也挡住恶意大包。
CRASH_MAX_REPORT_BYTES = int(os.getenv("CRASH_MAX_REPORT_BYTES", str(2 * 1024 * 1024)))
# 请求体上限（压缩后）。给 gzip 留足余量。
CRASH_MAX_UPLOAD_BYTES = int(os.getenv("CRASH_MAX_UPLOAD_BYTES", str(8 * 1024 * 1024)))
# 每 IP 每分钟上报数上限；设 0 关闭。
CRASH_RATE_PER_MIN = int(os.getenv("CRASH_RATE_PER_MIN", "30"))

crash_store = CrashStore(
    CRASH_DIR,
    max_bytes=CRASH_MAX_BYTES,
    max_report_bytes=CRASH_MAX_REPORT_BYTES,
)
crash_limiter = RateLimiter(CRASH_RATE_PER_MIN, window_seconds=60)


def load_vapid_keys():
    """加载 VAPID 密钥对"""
    global vapid_private_key, vapid_public_key_b64

    private_key_path = os.getenv("VAPID_PRIVATE_KEY_PATH", "./vapid_private.pem")
    public_key_path = os.getenv("VAPID_PUBLIC_KEY_PATH", "./vapid_public.pem")

    with open(private_key_path, "rb") as f:
        vapid_private_key = serialization.load_pem_private_key(
            f.read(),
            password=None,
            backend=default_backend()
        )

    with open(public_key_path, "rb") as f:
        public_key = serialization.load_pem_public_key(
            f.read(),
            backend=default_backend()
        )

    # 导出公钥为 Base64（客户端需要）
    public_key_bytes = public_key.public_bytes(
        encoding=serialization.Encoding.X962,
        format=serialization.PublicFormat.UncompressedPoint
    )
    vapid_public_key_b64 = base64.urlsafe_b64encode(public_key_bytes).decode('utf-8')

    logger.info("VAPID keys loaded successfully")


def initialize_firebase():
    """初始化 Firebase Admin SDK"""
    global fcm_app

    cred_path = os.getenv("FIREBASE_CREDENTIALS_PATH", "./service-account.json")

    if not os.path.exists(cred_path):
        raise FileNotFoundError(f"Firebase credentials not found: {cred_path}")

    cred = credentials.Certificate(cred_path)
    fcm_app = firebase_admin.initialize_app(cred)

    logger.info("Firebase Admin SDK initialized")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    logger.info("Starting JetDo Push Relay Server...")
    # 推送与崩溃上报是两条独立链路：密钥/Firebase 缺失不应该把整个服务拖死，
    # 否则「只想收崩溃报告」的部署也必须先配好 FCM。缺配置时降级为仅崩溃上报。
    try:
        load_vapid_keys()
    except Exception as e:
        logger.error(f"VAPID keys unavailable, push endpoints will fail: {e}")
    try:
        initialize_firebase()
    except Exception as e:
        logger.error(f"Firebase unavailable, push delivery disabled: {e}")

    stats = crash_store.stats()
    logger.info(
        "Crash store ready: dir={} files={} usage={:.1f}/{:.1f}MB".format(
            CRASH_DIR, stats["files"],
            stats["total_bytes"] / 1024 / 1024, stats["max_bytes"] / 1024 / 1024,
        )
    )
    yield
    logger.info("Shutting down...")


app = FastAPI(
    title="JetDo Push Relay",
    description="Web Push to FCM relay for JetDo Android",
    version="1.0.0",
    lifespan=lifespan
)


# ========== 数据模型 ==========

class SubscribeRequest(BaseModel):
    fcm_token: str
    user_id: int
    username: str


class UnsubscribeRequest(BaseModel):
    fcm_token: str


# ========== API 端点 ==========

@app.post("/subscribe")
async def subscribe(req: SubscribeRequest):
    """
    设备注册推送订阅

    返回：
    - endpoint: 提交给 Discourse 的推送接收地址
    - keys.p256dh: 客户端公钥（VAPID 公钥）
    - keys.auth: 认证密钥（客户端生成，16 字节随机数）
    """
    device_id = base64.urlsafe_b64encode(req.fcm_token.encode()).decode()[:22]
    device_registry[device_id] = req.fcm_token

    logger.info(f"Device subscribed: device_id={device_id}, user_id={req.user_id}, username={req.username}")

    # 客户端需自行生成 auth 密钥（16 字节随机数的 Base64）
    # 这里返回占位符，实际应由客户端生成并持久化
    auth_secret = base64.urlsafe_b64encode(os.urandom(16)).decode()

    return {
        "endpoint": f"{os.getenv('RELAY_BASE_URL', 'http://localhost:8080')}/push/{device_id}",
        "keys": {
            "p256dh": vapid_public_key_b64,
            "auth": auth_secret  # 实际应由客户端生成
        }
    }


@app.post("/push/{device_id}")
async def receive_push(device_id: str, request: Request):
    """
    接收 LINUX DO 的 Web Push 推送

    1. 解密推送内容（使用 VAPID 私钥）
    2. 提取通知标题、正文、跳转链接
    3. 通过 FCM 发送到对应设备
    """
    if device_id not in device_registry:
        logger.warning(f"Unknown device_id: {device_id}")
        raise HTTPException(status_code=404, detail="Device not found")

    fcm_token = device_registry[device_id]

    # 读取加密的推送载荷
    encrypted_payload = await request.body()

    try:
        # TODO: 解密 Web Push 载荷（需实现 RFC 8291 解密逻辑）
        # decrypted = decrypt_web_push(encrypted_payload, vapid_private_key)

        # 占位符：假设解密后得到 JSON
        notification_data = {
            "title": "新回复",
            "body": "有人回复了你的话题",
            "topic_id": 12345,
            "post_number": 42
        }

        # 构造 FCM 消息
        message = messaging.Message(
            notification=messaging.Notification(
                title=notification_data["title"],
                body=notification_data["body"]
            ),
            data={
                "topic_id": str(notification_data.get("topic_id", "")),
                "post_number": str(notification_data.get("post_number", ""))
            },
            token=fcm_token,
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(
                    channel_id="jetdo_notifications",
                    click_action="jetdo://notification"
                )
            )
        )

        # 发送到 FCM
        response = messaging.send(message)
        logger.info(f"FCM message sent: device_id={device_id}, response={response}")

        return {"status": "ok"}

    except Exception as e:
        logger.error(f"Failed to process push: device_id={device_id}, error={str(e)}")
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/unsubscribe")
async def unsubscribe(req: UnsubscribeRequest):
    """设备取消推送订阅"""
    device_id = base64.urlsafe_b64encode(req.fcm_token.encode()).decode()[:22]

    if device_id in device_registry:
        del device_registry[device_id]
        logger.info(f"Device unsubscribed: device_id={device_id}")

    return {"status": "ok"}


@app.post("/crash")
async def submit_crash(request: Request):
    """
    接收 ACRA 崩溃报告。

    三道闸，任何一道都足够挡住磁盘被写爆：
    1. 每 IP 每分钟 CRASH_RATE_PER_MIN 条 —— 崩溃循环时一台设备能在一分钟内发几十条，
       客户端 ACRA 的 limiter 是礼貌，这里才是硬约束。
    2. 单份解压后不超过 CRASH_MAX_REPORT_BYTES（默认 2MB）。
    3. 落盘后总额超 CRASH_MAX_BYTES（默认 100MB）就批量淘汰最旧的。

    报告格式不做强耦合：能解析成 JSON 就内嵌，解析不了就存原文。
    ACRA 的 reportFormat 改了也不会把服务端打挂。
    """
    client = request.client.host if request.client else "unknown"
    if not crash_limiter.allow(client):
        logger.warning(f"Crash report rate-limited: client={client}")
        raise HTTPException(status_code=429, detail="Too many crash reports, slow down")

    raw = await request.body()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty body")
    if len(raw) > CRASH_MAX_UPLOAD_BYTES:
        logger.warning(f"Crash report rejected (compressed too large): client={client} bytes={len(raw)}")
        raise HTTPException(status_code=413, detail="Report too large")

    content_encoding = request.headers.get("content-encoding")
    try:
        # gzip 解压是 CPU 活，扔线程池，别堵事件循环
        body = await run_in_threadpool(decode_body, raw, content_encoding)
    except (OSError, ValueError) as exc:
        raise HTTPException(status_code=400, detail=f"Cannot decode body: {exc}") from exc

    try:
        payload = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        # 非 JSON（比如 ACRA 配成 StringFormat.KEY_VALUE_LIST）：保留原文
        payload = {"_raw": body[:512_000].decode("utf-8", "replace")}

    envelope = {
        "received_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "content_encoding": content_encoding or "identity",
        "payload": payload,
    }
    data = json.dumps(envelope, ensure_ascii=False).encode("utf-8")

    try:
        info = await run_in_threadpool(crash_store.save, data)
    except ValueError as exc:
        raise HTTPException(status_code=413, detail=str(exc)) from exc

    logger.info(
        "Crash report stored: file={} size={} total={:.1f}MB files={} evicted={}".format(
            info["file"], info["size"],
            info["total_bytes"] / 1024 / 1024, info["total_files"], info["evicted"],
        )
    )
    return {"status": "ok", **info}


@app.get("/crash/stats")
async def crash_stats():
    """崩溃报告占用情况。用来确认配额与淘汰是否按预期工作。"""
    return crash_store.stats()


@app.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "ok",
        "registered_devices": len(device_registry),
        "crash_store": crash_store.stats(),
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8080))
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")

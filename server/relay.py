"""
ArkDO Android 推送中继服务器

接收 LINUX DO 的 Web Push 推送，解密后转发到 Firebase Cloud Messaging。

架构：
  LINUX DO ──Web Push（加密）──▶ 本中继 ──解密──▶ FCM ──▶ Android 设备

核心功能：
1. /subscribe - 设备注册推送订阅，返回 endpoint + VAPID 公钥
2. /push/{device_id} - 接收 Web Push，解密后转发给 FCM
3. /unsubscribe - 设备取消订阅
"""

import os
import json
import base64
import logging
from typing import Dict, Optional
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.backends import default_backend
import firebase_admin
from firebase_admin import credentials, messaging
from py_vapid import Vapid01
from dotenv import load_dotenv

# 加载环境变量
load_dotenv()

# 日志配置
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler("relay.log"),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# 全局变量
fcm_app = None
vapid_private_key = None
vapid_public_key_b64 = None
device_registry: Dict[str, str] = {}  # device_id -> fcm_token


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
    logger.info("Starting ArkDO Push Relay Server...")
    load_vapid_keys()
    initialize_firebase()
    yield
    logger.info("Shutting down...")


app = FastAPI(
    title="ArkDO Push Relay",
    description="Web Push to FCM relay for ArkDO Android",
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
                    channel_id="arkdo_notifications",
                    click_action="arkdo://notification"
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


@app.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "ok",
        "registered_devices": len(device_registry)
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8080))
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="info")

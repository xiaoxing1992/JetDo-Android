# API 适配文档

ArkDO Android 与 Discourse API 的对接文档。

## 基础信息

- **API 基址**：`https://linux.do`
- **认证方式**：Cookie（`_t` + `cf_clearance`）
- **网络层**：WebView Bridge（绕过 Cloudflare 防护）
- **数据格式**：JSON
- **序列化库**：kotlinx.serialization

## 核心端点

### 1. 话题列表

**最新话题**
```
GET /latest.json?page=0
```

**响应：**
```json
{
  "users": [...],
  "topic_list": {
    "topics": [
      {
        "id": 12345,
        "title": "话题标题",
        "posts_count": 42,
        "reply_count": 40,
        "views": 1234,
        "like_count": 56,
        "created_at": "2024-01-01T12:00:00.000Z",
        "last_posted_at": "2024-01-02T12:00:00.000Z",
        "posters": [...],
        "tags": ["tag1", "tag2"]
      }
    ],
    "more_topics_url": "/latest.json?page=1"
  }
}
```

**对应模型：**
- `TopicListResponse`
- `Topic`
- `User`

---

**热门话题**
```
GET /top.json?period=weekly
```

参数：
- `period`: `daily` / `weekly` / `monthly` / `yearly` / `all`

---

**未读话题**（需登录）
```
GET /unread.json
```

---

**新话题**（需登录）
```
GET /new.json
```

---

### 2. 话题详情

```
GET /t/{topic_id}.json?page={page}
```

**响应：**
```json
{
  "post_stream": {
    "posts": [
      {
        "id": 1,
        "username": "user1",
        "avatar_template": "/user_avatar/...",
        "created_at": "2024-01-01T12:00:00.000Z",
        "cooked": "<p>HTML 内容</p>",
        "post_number": 1,
        "reply_count": 5,
        "reads": 100,
        "reactions": [...]
      }
    ],
    "stream": [1, 2, 3, ...]
  },
  "details": {
    "can_edit": true,
    "can_delete": false,
    ...
  }
}
```

**对应模型：**
- `TopicDetailResponse`
- `Post`
- `Reaction`

---

### 3. 回复评论

```
POST /posts.json
Content-Type: application/json

{
  "topic_id": 12345,
  "raw": "回复内容",
  "reply_to_post_number": 10  // 可选，引用回复
}
```

**响应：**
```json
{
  "id": 67890,
  "created_at": "2024-01-03T12:00:00.000Z",
  ...
}
```

---

### 4. 表情回应

**添加回应**
```
PUT /discourse-reactions/posts/{post_id}/custom-reactions/{emoji}/toggle.json
```

参数：
- `emoji`: 表情 ID（如 `+1`、`heart`）

**响应：**
```json
{
  "success": true
}
```

---

### 5. 书签管理

**添加书签**
```
POST /bookmarks.json
Content-Type: application/json

{
  "bookmarkable_id": 12345,
  "bookmarkable_type": "Topic"
}
```

**删除书签**
```
DELETE /bookmarks/{bookmark_id}.json
```

**获取书签列表**
```
GET /u/{username}/bookmarks.json?page=0
```

---

### 6. 通知

**获取通知列表**
```
GET /notifications.json?recent=true&limit=50&offset=0
```

**响应：**
```json
{
  "notifications": [
    {
      "id": 1,
      "notification_type": 1,  // 1=mentioned, 2=replied, 5=liked, 等
      "read": false,
      "created_at": "2024-01-03T12:00:00.000Z",
      "post_number": 10,
      "topic_id": 12345,
      "data": {
        "topic_title": "话题标题",
        "display_username": "user1"
      }
    }
  ],
  "total_rows_notifications": 123,
  "seen_notification_id": 100
}
```

**标记已读**
```
PUT /notifications/mark-read.json
Content-Type: application/json

{
  "id": 1
}
```

---

### 7. 用户信息

**获取当前用户**
```
GET /session/current.json
```

**响应：**
```json
{
  "current_user": {
    "id": 123,
    "username": "user1",
    "name": "User One",
    "avatar_template": "/user_avatar/...",
    "trust_level": 2,
    "admin": false,
    "moderator": false
  }
}
```

**获取用户详情**
```
GET /u/{username}.json
```

---

### 8. 登录

**发起登录**
```
POST /session.json
Content-Type: application/json

{
  "login": "username",
  "password": "password"
}
```

**响应：**
- Cookie: `_t={session_token}`

**注意：** ArkDO 使用 WebView OAuth 登录，不直接调用此端点。

---

## WebView Bridge 调用示例

```kotlin
// 通过 WebView 桥接发起请求
val result = webViewBridge.fetch(
    url = "https://linux.do/latest.json?page=0",
    method = "GET",
    headers = mapOf(
        "Accept" to "application/json"
    )
)

result.onSuccess { json ->
    val response = Json.decodeFromString<TopicListResponse>(json)
    // 处理数据
}.onFailure { error ->
    // 错误处理
}
```

---

## 错误处理

### Cloudflare 挑战

**识别：** HTTP 503 + 响应体包含 `cf-challenge`

**处理流程：**
1. 触发 `ChallengeRecoveryCoordinator.startRecovery()`
2. 静默恢复（后台重载 + 探测）
3. 超时则显示手动验证页面

### 认证失效

**识别：** HTTP 403 或返回 `{"error": "You need to be logged in"}`

**处理流程：**
1. 清空本地会话
2. 弹出登录引导
3. 重新登录后自动重试原请求

### 网络异常

**识别：** 超时、DNS 解析失败、连接被拒绝

**处理流程：**
1. 检查 DoH 是否启用
2. 提示用户检查网络或开启 DoH
3. 提供"手动验证"入口

---

## 分页策略

**话题列表：** 基于 `page` 参数
```
/latest.json?page=0
/latest.json?page=1
...
```

**话题详情：** 基于 `post_ids` 范围
```
/t/12345.json  // 默认前 20 帖
/t/12345/21.json  // 21-40 帖
```

**通知列表：** 基于 `offset`
```
/notifications.json?offset=0&limit=50
/notifications.json?offset=50&limit=50
```

---

## 数据缓存策略

- **话题列表**：内存缓存 5 分钟，Room 本地缓存
- **话题详情**：内存缓存 3 分钟
- **用户信息**：Room 本地缓存，过期时间 1 小时
- **头像图片**：Coil 磁盘缓存 + 自定义 LRU 内存缓存

---

## 测试端点

**健康检查（不需要认证）：**
```
GET /srv/status
```

**响应：**
```json
{
  "ok": true
}
```

---

## 限流

Discourse API 有限流机制：
- **匿名用户**：每分钟 60 请求
- **已登录用户**：每分钟 200 请求

超限返回 `429 Too Many Requests`，需实现指数退避重试。

---

## 参考资料

- [Discourse API 官方文档](https://docs.discourse.org/)
- [Discourse Web Push 实现](https://github.com/discourse/discourse/blob/main/app/services/push_notification_pusher.rb)

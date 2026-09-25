# 消息通道连接器：微信 / 飞书 / QQ

> 本文说明本项目的三个 IM 连接器**怎么接、填什么、怎么验证**。
> 通道模型对齐 [OpenClaw](https://openclaw.ai) 的 `channels` 概念：一个通道 =
> 「一组凭据 + 一个投递端点」，凭据由用户在市场页填入，运行时统一由
> `ConnectorMessenger` 负责发送。

## 1. 总体模型

```
Agent / Skill / MCP
      │  connector_send_message · mcp__im__im_send
      ▼
ConnectorMessenger ──► 微信（ClawBot → OpenClaw Gateway ｜ 企业微信群机器人）
                   ──► 飞书（自定义机器人 webhook ｜ 自建应用 OpenAPI）
                   ──► QQ（QQ 开放平台官方机器人 OpenAPI v2）
                   ──► Telegram Bot
```

- 配置载体：`ConnectorDef`（`filesDir/mcp_config/connectors.json`），字段为
  `endpoint` + `apiKey` + `extra: Map<String, String>`；
- 通道选择：`ConnectorDef.id`（`wechat` / `feishu` / `qq` / `telegram`）；
- 模式选择：`extra["mode"]`（见下表，缺省按"开箱可用"的那一种）；
- 市场页入口：**市场 → 已安装管理 → 连接器 → 配置**（填 endpoint / apiKey /
  extra，并可「发送测试消息」真发一条验证）。

| id | mode | 走哪条路 | 需要的凭据 |
|----|------|----------|-----------|
| `wechat` | `wecom`（默认） | 企业微信群机器人 webhook | `apiKey`=Webhook key 或 `endpoint`=完整 webhook URL |
| `wechat` | `clawbot` | 微信官方 ClawBot 插件 → OpenClaw Gateway | `endpoint`=Gateway 地址，可选 `apiKey`=hooks token |
| `feishu` | `webhook`（默认） | 飞书自定义机器人（群） | `apiKey`=hook token 或 `endpoint`=完整 hook URL，可选 `extra.sign_secret` |
| `feishu` | `app` | 飞书自建应用机器人 OpenAPI | `extra.app_id` + `apiKey`=App Secret + `extra.target` |
| `qq` | `openapi`（唯一） | QQ 开放平台机器人 OpenAPI v2 | `extra.app_id` + `apiKey`=clientSecret + `extra.target` |
| `telegram` | — | Telegram Bot API | `apiKey`=Bot token + `extra.chat_id` |

> 个人微信没有官方开放 API——这也是 OpenClaw 走「ClawBot 插件 + 本地 Gateway」
> 的原因。本项目因此同时保留两条微信路径：**企业微信机器人**（零依赖、立刻可用）
> 与 **ClawBot 插件**（经你自己的 OpenClaw Gateway 投递到个人微信）。

## 2. 微信

### 2.1 企业微信群机器人（默认，`mode=wecom`）

1. 企业微信群 → 右上角 → 群机器人 → 添加机器人 → 复制 Webhook 地址
   （`https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<KEY>`）。
2. 市场页配置：`endpoint` 填完整 webhook URL（**或**保留默认 endpoint、
   `apiKey` 填 `<KEY>`）。
3. 发送：`{"msgtype":"text","text":{"content":"..."}}`；`markdown=true` 时改发
   `{"msgtype":"markdown","markdown":{"content":"..."}}`；
   `extra.mentioned_list`（逗号分隔，如 `@all`）用于 @ 提醒。
4. 成功判定：HTTP 2xx 且 `errcode == 0`。限频 20 条/分钟。

官方：[消息推送配置说明](https://developer.work.weixin.qq.com/document/9000/90136/91770)

### 2.2 微信 ClawBot（OpenClaw 同款，`mode=clawbot`）

OpenClaw 的接法（本项目复用其 Gateway 作为投递侧）：

1. 安装插件：
   `npx -y @tencent-weixin/openclaw-weixin-cli@latest install`
   （或 `openclaw plugins install "@tencent-weixin/openclaw-weixin"` 后
   `openclaw config set plugins.entries.openclaw-weixin.enabled true`）；
2. 扫码授权：`openclaw channels login --channel openclaw-weixin`
   （每次扫码 = 一个新微信账号，多账号上下文隔离）；
3. 重启网关：`openclaw gateway restart`；微信端：我 → 设置 → 插件 → ClawBot。

在本项目里配置：

| 字段 | 值 |
|------|-----|
| `endpoint` | OpenClaw Gateway 地址，如 `http://192.168.1.10:18789` |
| `extra.mode` | `clawbot` |
| `apiKey` / `extra.hook_token` | Gateway hooks token（启用 `hooks.enabled` 时必填） |
| `extra.hook_path` | 默认 `/hooks/agent` |
| `extra.channel` | 默认 `openclaw-weixin` |
| `extra.target` | 可选：多微信账号时指定接收者 |

发送即向 Gateway 的 hooks 端点 POST `{"message": ..., "channel": ..., "to": ...}`，
由 Gateway 投递到微信；成功判定为 HTTP 2xx。

> 注意：ClawBot 插件与 OpenClaw 版本存在兼容区间（社区反馈需 < 3.22），
> 以插件 README 为准。

## 3. 飞书

### 3.1 自定义机器人（默认，`mode=webhook`）

1. 飞书群 → 设置 → 群机器人 → 添加自定义机器人 → 复制 webhook
   （`https://open.feishu.cn/open-apis/bot/v2/hook/<TOKEN>`）。
2. 市场页：`endpoint` 填完整 hook URL（**或**默认 endpoint + `apiKey`=<TOKEN>）。
3. **签名校验**：若机器人开启了该安全设置，把密钥填到 `extra.sign_secret`，
   运行时会按官方算法追加 `timestamp` + `sign`：

   ```
   sign = Base64( HmacSHA256(key = "{timestamp}\n{secret}", data = "") )
   ```

   注意是 **把 `timestamp\nsecret` 当作 HMAC 的 key、对空串取摘要**，
   不是对消息体取摘要——写反会恒定 19021。
4. 成功判定：HTTP 2xx 且 `code == 0`；限频 100 次/分钟、5 次/秒，请求体 ≤ 20KB。

官方：[自定义机器人使用指南](https://open.feishu.cn/document/ukTMukTMukTM/ucTM5YjL3ETO24yNxkjN)

### 3.2 自建应用机器人（`mode=app`，OpenClaw `channels.feishu` 同款）

1. [飞书开放平台](https://open.feishu.cn) → 创建**企业自建应用** → 启用机器人能力；
2. 权限：开通「获取与发送单聊、群组消息」（`im:message`）等，创建版本并发布；
3. 拿到 **App ID**（`cli_xxx`）与 **App Secret**；
4. 市场页填写：

   | 字段 | 值 |
   |------|-----|
   | `extra.mode` | `app` |
   | `extra.app_id` | `cli_xxx` |
   | `apiKey` | App Secret |
   | `extra.target` | `open_id`（可写 `openid:xxx`）或群 `chat_id`（`oc_xxx`，可写 `chat:xxx`） |

运行时链路（token 进程内缓存，到期前 5 分钟刷新）：

```
POST https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal
     {"app_id": "...", "app_secret": "..."}   → tenant_access_token
POST https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id|chat_id
     Authorization: Bearer <token>
     {"receive_id": "...", "msg_type": "text", "content": "{\"text\":\"...\"}"}
```

`content` 必须是 **JSON 字符串**（不是嵌套对象）——这是最常踩的坑。

> 接收用户消息需要事件订阅（长连接 WebSocket 或 Webhook 回调），本版只实现
> **发送侧**；接收侧按官方 SDK 接入后可复用同一套凭据。

## 4. QQ 官方机器人（`qq`）

1. 打开 [QQ 开放平台](https://q.qq.com)（机器人入口：
   `https://q.qq.com/qqbot/openclaw/login.html`）→ 扫码登录 → 创建机器人；
2. 记录 **AppID** 与 **AppSecret**（Secret 只显示一次）；
3. 市场页填写：

   | 字段 | 值 |
   |------|-----|
   | `extra.app_id` | AppID |
   | `apiKey` | AppSecret（clientSecret） |
   | `extra.target` | `group:<群 openid>` / `user:<用户 openid>` / `channel:<子频道 id>`（裸 openid 默认按群处理） |

运行时链路（token 缓存，临近过期 60s 内刷新）：

```
POST https://api.bot.qq.com/app/getAppAccessToken
     {"appId": "...", "clientSecret": "..."}   → access_token（7200s）
POST https://api.bot.qq.com/v2/groups/{group_openid}/messages
POST https://api.bot.qq.com/v2/users/{openid}/messages
POST https://api.bot.qq.com/channels/{channel_id}/messages
     Authorization: QQBot <access_token>
     {"content": "...", "msg_type": 0}         （markdown=true 时 msg_type=2）
```

限制：主动消息受机器人认证类型与场景限频（群聊约 30–60 条/分钟）；子频道主动
推送要求机器人 WebSocket 在线。接收消息需 WebSocket/Webhook 事件订阅，本版
只实现发送侧。

官方：[QQ 机器人文档](https://bot.q.qq.com/wiki/) ·
[接口调用与鉴权](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/api-use.html)

## 5. 怎么用

- **对话里**：`connector_list` 看通道与凭据状态 → `connector_send_message`
  （`verify_only=true` 只校验不发；`markdown=true` 发富文本）。
- **MCP**：内置 `im` 服务器提供 `im_list_channels` / `im_send` / `im_verify`，
  外部 MCP 客户端也能复用同一份配置。
- **技能**：内置模板 `im_notify`（结果推送到 IM）把这一步固化成可重复流程。
- **市场页**：连接器 → 配置 → 填完点「发送测试消息」，回显 ✅/❌ 即最终验收。

## 6. 排错清单

| 现象 | 常见原因 |
|------|----------|
| 飞书 `code 19021` | 签名算法写反（应是 `timestamp\nsecret` 作 key）或时间戳偏差 > 1 小时 |
| 飞书 `code 19024` | 开启了自定义关键词，消息里不含关键词 |
| 飞书自建应用 `code 99991672` 等 | App 未发布版本 / 权限未开通 / `content` 传成了对象 |
| 企业微信 `errcode 93000` | webhook key 错误或机器人被删；注意 20 条/分钟限频 |
| 微信 ClawBot 无响应 | Gateway 未启动 / hooks token 不对 / OpenClaw 与插件版本不兼容 |
| QQ 401/403 | AppSecret 错误或 token 过期；IP 白名单未放行服务器出口 IP |
| QQ 发不出子频道消息 | 频道主动推送要求机器人 WebSocket 在线 |

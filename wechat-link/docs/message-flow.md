# 完整消息流转图

---

## 第一层：微信 → SDK → WechatBotRunner（消息接收）

```
微信用户发消息
    ↓
微信服务端 (iLink API)
    ↓
SDK 心跳轮询 (HeartbeatService → UpdateService.poll())
    ↓ HTTP GET → JSON 解析
List<WeixinMessage> 对象列表
    ↓
OnMessageListener.onMessages(List<WeixinMessage>)
    ↓
WechatBotRunner.handleMessage(WeixinMessage msg)
```

**WeixinMessage 内部结构**：
```
WeixinMessage
├── from_user_id: String     ("abc123@im.wechat")
├── context_token: String    (SDK 内部自动缓存，发送时自动使用)
└── item_list: List<MessageItem>
     └── MessageItem
          ├── type: int
          ├── text_item: TextItem { text: String }
          ├── image_item: ImageItem { media: CDNMedia }
          ├── voice_item: VoiceItem { media: CDNMedia, text: String, playtime: Integer }
          ├── file_item: FileItem { media: CDNMedia, file_name: String, len: String }
          └── video_item: VideoItem { media: CDNMedia }
```

---

## 第二层：WechatBotRunner 消息分发（按字段判断类型）

```
MessageItem
    ↓ 判断哪个字段不为 null
    ├── text_item != null  ─────→ handleText()
    ├── image_item != null ─────→ handleImage()
    ├── voice_item != null ─────→ handleVoice()
    ├── file_item != null  ─────→ handleFile()
    └── video_item != null ─────→ "暂不支持视频"
```

---

## 路径 A：纯文本消息

```
handleText(userId, item)
    ↓
item.getText_item().getText() → String "你好"
    ↓
callLLM(userId, "你好", "TEXT", null)
    ↓ 构建 LLMRequest {userId, sessionId, content="你好", messageType="TEXT", mediaUrl=null}
LLMMessageFacade.handleMessage(request)
    ↓ messageType == "TEXT"
AdaptiveLLMRouterImpl.route(userId, "你好")
    ↓
    ├─ 1. 前缀检测: 不以 /draw /imag /think /deep 开头 → 跳过
    ├─ 2. 关键词分析: "你好" 不含 "画/绘/生成一张" 等 → IntentType.PURE_TEXT
    └─ 3. 走 fallbackToText()
         ↓
    SimpleLLMClientImpl.chat(request)
         ↓ 构建 OpenAI 格式 JSON:
         {
           "model": "deepseek-v4-flash",
           "messages": [
             {"role":"system", "content":"你是一个智能微信助手..."},
             {"role":"user", "content":"你好"}
           ],
           "max_tokens": 2048,
           "temperature": 0.7
         }
         ↓ WebClient POST → https://.../compatible-mode/v1/chat/completions
         ↓ 响应 JSON → 提取 choices[0].message.content
    LLMResponse { status="SUCCESS", content="你好！有什么可以帮你的？" }
         ↓
sendReply(userId, response)
    ↓ response.hasImages() == false, content 不是图片 URL
sendTextSafe(userId, "你好！有什么可以帮你的？")
    ↓
client.sendText("abc123@im.wechat", "你好！有什么可以帮你的？")
    ↓ SDK 内部: 自动取缓存的 contextToken → 构建 JSON → HTTP POST → iLink API
微信用户收到文字回复
```

**数据形态变化**：`String → LLMRequest → JSON body → HTTP → JSON response → String → HTTP POST → 微信`

---

## 路径 B：文生图（前缀触发）

```
handleText(userId, item)
    ↓
text = "/draw 一只橘猫在窗台晒太阳"
    ↓
callLLM(userId, "/draw 一只橘猫在窗台晒太阳", "TEXT", null)
    ↓
LLMMessageFacade → AdaptiveLLMRouterImpl.route()
    ↓
    ├─ 1. 前缀检测: startsWith("/draw ") → 命中！
    │      prompt = "一只橘猫在窗台晒太阳"（去掉前缀）
    └─ 2. executeTTI("一只橘猫在窗台晒太阳")
         ↓
    TTIModelSelector.generate("一只橘猫在窗台晒太阳")
         ↓ 读取配置 active-engine=FLUX → 找到 FluxImageStrategy
    FluxImageStrategy.generateImage("一只橘猫在窗台晒太阳")
         ↓ 构建请求 JSON:
         {
           "model": "black-forest-labs/FLUX.1-schnell",
           "prompt": "一只橘猫在窗台晒太阳",
           "image_size": "1024x1024"
         }
         ↓ WebClient POST → https://api.siliconflow.cn/v1/images/generations
         ↓ Header: Authorization: Bearer sk-你的key
         ↓ 响应 JSON → 提取 images[].url 或 data[].url
    LLMResponse { status="SUCCESS", content="图片已生成：", imageUrls=["https://cdn.xxx/img.png"] }
         ↓
sendReply(userId, response)
    ↓ response.hasImages() == true
    ├─ sendTextSafe(userId, "图片已生成：")
    └─ for each url in imageUrls:
         sendImageFromUrl(userId, "https://cdn.xxx/img.png")
              ↓
         downloadBytes(url)  →  HTTP GET → byte[] (图片原始二进制)
              ↓
         client.sendImage(userId, byte[], "img_170xxx.jpg", null)
              ↓ SDK 内部:
              │  1. 生成随机 16 字节 AES 密钥 (hex)
              │  2. AES/ECB/PKCS5Padding 加密 byte[]
              │  3. POST /ilink/bot/getuploadurl 获取 CDN 上传地址
              │  4. PUT 加密后的 byte[] 到 CDN
              │  5. 构建 SendMessageRequest (含 CDNMedia) → POST /ilink/bot/sendmessage
              ↓
微信用户收到图片
```

**数据形态变化**：`String → prompt String → JSON → HTTP → URL String → HTTP GET → byte[] → AES 加密 → CDN 上传 → 消息发送 → 微信`

---

## 路径 C：文生图（关键词触发）

```
text = "帮我画一只柴犬"
    ↓
AdaptiveLLMRouterImpl.route()
    ├─ 前缀检测: 无匹配
    ├─ 关键词分析: "画" 命中 TTI_KEYWORDS → IntentType.TEXT_TO_IMAGE
    └─ executeSpecialized() → case TEXT_TO_IMAGE
         ↓
    extractImagePrompt("帮我画一只柴犬")
         → 匹配前缀词 "帮我画" → 去掉 → prompt = "一只柴犬"
         ↓
    executeTTI("一只柴犬")
         ↓ (后续同路径 B)
```

---

## 路径 D：深度思考 / 多模态文本

```
text = "/think 分析一下量子计算的前景"
    ↓
AdaptiveLLMRouterImpl.route()
    ├─ 前缀检测: startsWith("/think ") → 命中
    │      prompt = "分析一下量子计算的前景"
    └─ executeMultimodal(userId, prompt)
         ↓
    SimpleLLMClientImpl.chat(request)  ← 当前降级为普通文本模型
         ↓                               未来接入 GPT-4o 等多模态模型
    (后续同路径 A 的文本对话流程)
```

---

## 路径 E：图片消息

```
handleImage(userId, item)
    ↓
client.downloadImageFromMessageItem(item)
    ↓ SDK 内部:
    │  1. 取 item.getImage_item().getMedia() → CDNMedia { encrypt_query_param, aes_key }
    │  2. GET https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=xxx
    │  3. 拿到加密 byte[]
    │  4. Base64.decode(aes_key) → 16 字节密钥
    │  5. AES/ECB/PKCS5Padding 解密 → 原始图片 byte[]
    ↓
byte[] imageBytes (原始 JPEG/PNG 数据)
    ↓
Base64.getEncoder().encodeToString(imageBytes)
    → "data:image/jpeg;base64,/9j/4AAQ..." (String, 可能很长)
    ↓
callLLM(userId, "请描述这张图片的内容", "IMAGE", dataUri)
    ↓ LLMRequest { messageType="IMAGE", mediaUrl="data:image/jpeg;base64,..." }
LLMMessageFacade → messageType=="IMAGE" → handleMultiModalMessage()
    ↓ 查找 parser.supports("IMAGE") → ImageModelParser
ImageModelParser.parse(dataUri)
    ↓ 构建多模态请求 JSON (OpenAI vision 格式):
    {
      "model": "deepseek-v4-flash",
      "messages": [
        {"role":"system", "content":"你是一个图片识别助手..."},
        {"role":"user", "content": [
          {"type":"text", "text":"请描述这张图片的内容。"},
          {"type":"image_url", "image_url":{"url":"data:image/jpeg;base64,..."}}
        ]}
      ]
    }
    ↓ WebClient POST → LLM API
    ↓ 响应: "这是一张橘猫在窗台上晒太阳的照片..."
LLMResponse { status="SUCCESS", content="这是一张橘猫在窗台上晒太阳的照片..." }
    ↓
sendTextSafe(userId, "这是一张橘猫在窗台上晒太阳的照片...")
    ↓
client.sendText() → 微信用户收到文字描述
```

**数据形态变化**：`CDNMedia → HTTP GET → 加密byte[] → AES解密 → 明文byte[] → Base64 String → JSON body → HTTP → String回复 → HTTP POST → 微信`

---

## 路径 F：语音消息

```
handleVoice(userId, item)
    ↓
item.getVoice_item()
    ├── getText() → "今天天气怎么样" (微信端语音转文字，可能为 null)
    └── getPlaytime() → 3000 (毫秒)
    ↓
    ├─ transcript != null && 非空:
    │      callLLM(userId, "今天天气怎么样", "TEXT", null)
    │      ↓ (走路径 A 的纯文本对话流程)
    │
    └─ transcript == null:
         sendTextSafe(userId, "收到语音消息，但未识别到文字内容...")
         ↓
         client.sendText() → 微信用户收到提示
```

**注意**：语音的 `media` 字段（CDNMedia）里存的是原始音频数据，可以用 `client.downloadVoiceFromMessageItem(item)` 下载为 byte[]（OGG/SILK 格式），但当前未接入 ASR 服务自行转写。

---

## 路径 G：文件消息

```
handleFile(userId, item)
    ↓
item.getFile_item()
    ├── getFile_name() → "报表.xlsx"
    └── getLen() → "102400" (字节数字符串)
    ↓
prompt = "用户发送了一个文件：报表.xlsx。请告诉用户你已收到..."
    ↓
callLLM(userId, prompt, "TEXT", null)
    ↓ messageType=="TEXT" → AdaptiveLLMRouterImpl.route()
    ↓ 关键词分析: 不含生图关键词 → IntentType.PURE_TEXT
    ↓ fallbackToText() → SimpleLLMClientImpl.chat()
    ↓ LLM 回复: "已收到你的文件「报表.xlsx」，请问需要我帮你..."
    ↓
sendTextSafe() → client.sendText() → 微信用户收到回复
```

**注意**：文件的原始二进制也可以通过 `client.downloadFileFromMessageItem(item)` 下载为 byte[]，未来可接入 PDF 解析/OCR 等。

---

## 数据形态总结

| 阶段 | 数据形态 |
|---|---|
| 微信服务端 → SDK | HTTP JSON → 反序列化为 Java 对象 |
| 消息体中的文本 | `String`（直接在 JSON 里） |
| 消息体中的媒体 | `CDNMedia` 对象 → HTTP 下载 → 加密 `byte[]` → AES 解密 → 明文 `byte[]` |
| 图片传给 LLM | `byte[]` → Base64 编码 → `String`（data URI） |
| 文本传给 LLM | `String` → 放入 JSON messages → HTTP POST |
| LLM 文本回复 | HTTP JSON → 提取 `String` |
| LLM 图片回复 | HTTP JSON → 提取 URL `String` → HTTP GET → `byte[]` |
| 发文本给微信 | `String` → SDK 构建 JSON → HTTP POST → iLink API |
| 发图片给微信 | `byte[]` → AES 加密 → 上传 CDN → 构建消息 JSON → HTTP POST |

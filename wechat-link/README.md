# wechat-link

微信智能机器人项目 —— 基于 Spring Boot + LLM 大语言模型的微信消息处理系统。

## 架构总览

```
com.wechat.link
├── bot/
│   └── WechatBotRunner.java          # 微信消息监听（已改造，接入 LLM）
└── llm/
    ├── config/
    │   ├── LLMConfig.java             # WebClient Bean 配置
    │   └── LLMProperties.java         # 配置属性绑定（环境变量）
    ├── dto/
    │   ├── LLMRequest.java            # 统一请求 DTO
    │   └── LLMResponse.java           # 统一响应 DTO
    ├── exception/
    │   └── LLMException.java          # 自定义异常
    ├── client/
    │   ├── LLMClient.java             # 对话接口
    │   └── SimpleLLMClientImpl.java   # OpenAI 兼容实现（智谱/DeepSeek/Ollama）
    ├── multimodal/
    │   ├── MultiModalParser.java      # 策略模式接口
    │   ├── ImageModelParser.java      # 图片解析（占位）
    │   ├── VideoModelParser.java      # 视频解析（占位）
    │   └── DocumentModelParser.java   # 文档解析（占位）
    ├── agent/
    │   ├── AgentService.java          # Agent 接口
    │   ├── AgentContext.java          # 上下文封装
    │   └── DefaultAgentExecutor.java  # 骨架占位
    └── facade/
        └── LLMMessageFacade.java      # 统一调度门面（唯一入口）
```

## 关键设计决策

1. **环境变量安全配置** — `application.yml` 中所有敏感配置使用 `${ENV_VAR:default}` 占位符，通过 `LLMProperties` + `@ConfigurationProperties` 类型安全加载。

2. **策略模式** — `MultiModalParser<T>` 接口通过 `supports()` 方法实现运行时动态匹配，新增媒体类型只需添加一个 `@Component` 实现类，零改动原有代码。

3. **门面模式** — `LLMMessageFacade` 是微信消息进入 LLM 层的唯一入口，屏蔽了内部路由细节，`WechatBotRunner` 只需构造 `LLMRequest` 即可。

4. **OpenAI 兼容协议** — `SimpleLLMClientImpl` 对接标准的 `/chat/completions` 接口，兼容智谱 GLM、DeepSeek、OpenAI、Ollama 等。

## 使用方式

设置环境变量后启动即可：

```bash
set LLM_API_KEY=your-api-key-here
set LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
set LLM_MODEL=glm-4-flash
mvn spring-boot:run
```

文本消息将自动通过 LLM 生成回复，图片/视频/文件暂返回"功能开发中"提示，待逐步实现具体解析器即可。

## 扩展指南

### 新增多模态解析器

实现 `MultiModalParser<String>` 接口并标注 `@Component`，无需修改其他代码：

```java
@Component
public class MyNewParser implements MultiModalParser<String> {
    @Override
    public boolean supports(String mediaType) {
        return "NEW_TYPE".equalsIgnoreCase(mediaType);
    }

    @Override
    public LLMResponse parse(String mediaUrl) {
        // 具体解析逻辑
    }
}
```

### 启用 Agent 模块

在 `LLMMessageFacade.handleTextMessage()` 中取消注释 Agent 路由判断，并实现 `AgentService` 接口的具体逻辑。

### 切换 LLM 提供商

只需修改环境变量，无需改代码：

| 提供商 | LLM_BASE_URL | LLM_MODEL |
|--------|-------------|-----------|
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Ollama（本地） | `http://localhost:11434/v1` | `qwen2` |

package com.wechat.link.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置属性类
 * <p>
 * 所有敏感信息通过环境变量注入，禁止硬编码。
 * 使用方式：在 application.yml 中通过 ${ENV_VAR:default} 占位符绑定。
 * </p>
 *
 * @author wechat-link
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LLMProperties {

    /** LLM API Key，从环境变量 LLM_API_KEY 读取 */
    private String apiKey;

    /** LLM 服务基础 URL */
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 使用的模型名称 */
    private String model = "glm-4-flash";

    /** 请求超时时间（秒） */
    private Integer timeout = 30;

    /** 最大 Token 数 */
    private Integer maxTokens = 2048;

    /** 温度参数 */
    private Double temperature = 0.7;

    /** 系统预设 Prompt */
    private String systemPrompt = "你是一个智能微信助手，请用简洁友好的语言回复用户的问题。";
}

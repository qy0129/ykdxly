package com.wechat.link.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 全局配置属性
 * <p>
 * 涵盖：路由策略、默认文本模型、多模态模型、文生图模型、
 * 记忆模块参数、语音 ASR / TTS 模型
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LLMProperties {

    // ==================== 路由策略 ====================
    /** 路由模式: AUTO / MULTIMODAL / SPECIALIZED */
    private String routingMode = "AUTO";

    // ==================== 默认纯文本模型（极速低成本） ====================
    /** LLM API Key（百炼 / SiliconFlow / OpenAI 等） */
    private String apiKey;
    /** LLM API 端点 */
    private String baseUrl = "https://api.openai.com/v1";
    /** 默认文本模型：百炼→deepseek-v4-flash / qwen-turbo */
    private String model = "gpt-3.5-turbo";
    /** 请求超时秒数 */
    private Integer timeout = 30;
    /** 最大输出 token 数 */
    private Integer maxTokens = 2048;
    /** 采样温度（0~2，值越高越随机） */
    private Double temperature = 0.7;
    /** 系统提示词 */
    private String systemPrompt = "你是一个智能微信助手，请用简洁友好的语言回复用户的问题。";

    // ==================== 多模态/深度思考模型 ====================
    private MultimodalConfig multimodal = new MultimodalConfig();

    // ==================== 文生图模型 ====================
    private TTIConfig tti = new TTIConfig();

    // ==================== 记忆模块参数 ====================
    /** 每用户最大记忆条数（ChatMemoryManager） */
    private Integer memoryMaxSize = 10;
    /** 记忆衰减优化器中保留完整数据的近期窗口数（MultiModalMemoryOptimizer） */
    private Integer memoryRecentWindow = 4;

    // ==================== 文档处理配置 ====================
    private DocumentConfig document = new DocumentConfig();

    // ==================== 语音 ASR / TTS 模型 ====================
    private VoiceConfig voice = new VoiceConfig();

    // ==================== 内部配置类 ====================

    @Data
    public static class MultimodalConfig {
        /** 多模态 LLM API Key（通常与默认模型共用） */
        private String apiKey;
        /** 多模态 LLM API 端点 */
        private String baseUrl = "https://api.openai.com/v1";
        /** 多模态模型：百炼→qwen3.7-plus / qwen-vl-max */
        private String model = "gpt-4o";
        /** 多模态请求超时秒数（图片分析耗时较长） */
        private Integer timeout = 60;
        /** 多模态最大输出 token 数 */
        private Integer maxTokens = 4096;
    }

    @Data
    public static class TTIConfig {
        /** 激活的文生图引擎 */
        private String activeEngine = "DALLE";
        private DalleConfig dalle = new DalleConfig();
    }

    @Data
    public static class DalleConfig {
        /** 百炼 API Key（需开通通义万相文生图服务） */
        private String apiKey;
        /** 百炼兼容模式端点 */
        private String baseUrl = "https://api.openai.com/v1";
        /** 文生图模型：百炼→qwen-image-2.0 / dall-e-3 */
        private String model = "dall-e-3";
        /** 图片尺寸：256x256 / 512x512 / 1024x1024 等 */
        private String size = "1024x1024";
        /** 图片质量：standard / hd（仅 dall-e-3 支持 hd） */
        private String quality = "standard";
        /** 文生图请求超时秒数（图片生成耗时较长） */
        private Integer timeout = 60;
    }

    @Data
    public static class DocumentConfig {
        /** Python 解释器路径（默认使用系统 PATH 中的 python） */
        private String pythonPath = "python";
        /** Markdown 转文档脚本路径 */
        private String convertScript = "scripts/markdown_to_docx.py";
        /** 文档转换超时秒数 */
        private Integer timeout = 60;
        /** Tika 提取文本最大字符数（超过则截断） */
        private Integer maxExtractChars = 50000;
        /** 记忆占位符最大长度（防 Token 爆炸） */
        private Integer memoryPlaceholderLength = 200;
    }

    @Data
    public static class VoiceConfig {
        /** ASR（语音转文字）配置 */
        private AsrConfig asr = new AsrConfig();
        /** TTS（文字转语音）配置 */
        private TtsConfig tts = new TtsConfig();
    }

@Data
public static class AsrConfig {
    /** 是否启用自定义 ASR 兜底（微信原生 ASR 返回 null 时自动启用） */
    private Boolean enabled = true;
    /** 百炼平台 API Key（需开通语音识别服务） */
    private String apiKey;
    /** 百炼兼容模式端点：https://<instance>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1 */
    private String baseUrl = "https://api.openai.com/v1";
    /** ASR 模型：百炼→qwen-audio-turbo / whisper-1 */
    private String model = "whisper-1";
    /** ASR 请求超时秒数 */
    private Integer timeout = 30;
    /** 音频语言（zh / en / auto 表示自动检测） */
    private String language = "zh";
}

@Data
public static class TtsConfig {
    /** 是否启用 TTS 语音回复 */
    private Boolean enabled = true;
    /** 百炼平台 API Key（需开通语音合成服务） */
    private String apiKey;
    /** 百炼兼容模式端点：https://<instance>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1 */
    private String baseUrl = "https://api.openai.com/v1";
    /** TTS 模型：百炼→cosyvoice-v1 / tts-1 */
    private String model = "tts-1";
    /**
     * 发音人音色（百炼 Qwen-TTS）
     * <p>
     * 标准音色：
     * <ul>
     *   <li>Cherry / 芊悦 — 阳光积极小姐姐（女）</li>
     *   <li>Serena / 苏瑶 — 温柔小姐姐（女）</li>
     *   <li>Ethan / 晨煦 — 阳光温暖大男孩（男）</li>
     *   <li>Chelsie / 千雪 — 二次元虚拟女友（女）</li>
     *   <li>MoMo / 茉兔 — 撒娇搞怪（女）</li>
     *   <li>Vivian / 十三 — 拽拽可爱小暴躁（女）</li>
     *   <li>Moon / 月白 — 率性帅气（男）</li>
     *   <li>Maia / 四月 — 知性温柔（女）</li>
     *   <li>Kai / 凯 — 磁性沉稳（男）</li>
     *   <li>Bella / 萌宝 — 小萝莉（女）</li>
     *   <li>Neil / 阿闻 — 新闻主持（男）</li>
     *   <li>Seren / 小婉 — 助眠舒缓（女）</li>
     *   <li>Stella / 少女阿月 — 甜美元气少女（女）</li>
     * </ul>
     * 方言音色：Jada/上海-阿珍、Dylan/北京-晓东、Sunny/四川-晴儿、Rocky/粤语-阿强、Kiki/粤语-阿清 等
     * </p>
     */
    private String voice = "alloy";
    /** 输出音频格式：mp3（推荐，微信支持）/ opus / aac / flac */
    private String responseFormat = "mp3";
    /** TTS 请求超时秒数 */
    private Integer timeout = 30;
    /** 用户发送语音时是否自动用语音回复（需 enabled=true） */
    private Boolean autoVoiceReply = true;
}
}

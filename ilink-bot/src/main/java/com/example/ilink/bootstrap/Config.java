package com.example.ilink.bootstrap;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 应用配置中心。
 *
 * <p>从项目运行目录下的 {@code config.properties} 读取 API Key、模型、
 * 音频目录和超时时间等配置；配置文件不存在或 API Key 无效时，程序会在
 * 启动阶段直接提示错误。</p>
 */
public class Config {

    private static final Path CONFIG_PATH = locateConfigPath();

    public static final String API_KEY = loadApiKey();
    public static final String API_BASE_URL = "https://api.siliconflow.cn/v1/chat/completions";
    public static final String MODEL = "Qwen/Qwen3-8B";
    public static final String ROUTER_MODEL = loadProperty("router.model", "Qwen/Qwen3.5-9B");
    public static final String VISION_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    public static final String DRAW_API_URL = "https://api.siliconflow.cn/v1/images/generations";
    public static final String DRAW_MODEL = "Kwai-Kolors/Kolors";
    public static final String IMAGE_EDIT_MODEL = "Qwen/Qwen-Image-Edit-2509";
    public static final String AUDIO_TRANSCRIPTION_URL = "https://api.siliconflow.cn/v1/audio/transcriptions";
    public static final String AUDIO_TRANSCRIPTION_MODEL = "FunAudioLLM/SenseVoiceSmall";
    public static final String TTS_API_URL = "https://api.siliconflow.cn/v1/audio/speech";
    public static final String TTS_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    public static final String TTS_VOICE = loadProperty("tts.voice.default", "FunAudioLLM/CosyVoice2-0.5B:alex");
    public static final String TTS_VOICE_BOY = loadProperty("tts.voice.boy", TTS_VOICE);
    public static final String TTS_VOICE_GIRL = loadProperty("tts.voice.girl", TTS_VOICE);
    public static final String TTS_VOICE_MALE = loadProperty("tts.voice.male", TTS_VOICE);
    public static final String TTS_VOICE_FEMALE = loadProperty("tts.voice.female", TTS_VOICE);
    public static final String TTS_VOICE_WARM = loadProperty("tts.voice.warm", TTS_VOICE);
    public static final String TTS_VOICE_LIVELY = loadProperty("tts.voice.lively", TTS_VOICE);
    public static final String REPLY_MODE = loadProperty("reply.mode", "text");
    public static final boolean AUDIO_ANALYSIS_ONLY_WHEN_REQUESTED =
            Boolean.parseBoolean(loadProperty("audio.analysis.only_when_requested", "true"));
    public static final Path AUDIO_DIR = Path.of(loadProperty("audio.dir", "data/audio"));
    public static final Path MEDIA_DIR = Path.of(loadProperty("media.dir", "data/media"));
    public static final String FFMPEG_COMMAND = loadProperty("ffmpeg.command", "ffmpeg");
    public static final String SILK_DECODER_COMMAND = loadProperty("silk.decoder.command", "auto");
    public static final String DOCUMENT_MODEL = loadProperty("document.model", "deepseek-ai/DeepSeek-Coder-V2-Instruct");
    public static final int DOCUMENT_MAX_TEXT_CHARS = Integer.parseInt(loadProperty("document.max_text_chars", "40000"));
    public static final Duration REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("request.timeout.seconds", "180")));
    public static final Duration ROUTER_REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("router.request.timeout.seconds", "30")));
    public static final Duration ROUTER_TOTAL_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("router.total.timeout.seconds", "35")));
    public static final int ROUTER_MAX_TOKENS = Integer.parseInt(
            loadProperty("router.max_tokens", "1800"));
    public static final Duration TODO_PLANNER_REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("todo.planner.request.timeout.seconds", "30")));
    public static final int TODO_PLANNER_MAX_TOKENS = Integer.parseInt(
            loadProperty("todo.planner.max_tokens", "900"));
    public static final boolean REFLECTION_AI_ENABLED =
            Boolean.parseBoolean(loadProperty("reflection.ai.enabled", "true"));
    public static final Duration REFLECTION_AI_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("reflection.ai.timeout.seconds", "30")));
    public static final int REFLECTION_AI_MAX_TOKENS = Integer.parseInt(
            loadProperty("reflection.ai.max_tokens", "1000"));
    public static final Duration AUTOMATION_TASK_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("automation.task.timeout.seconds", "300")));
    public static final Duration AUTOMATION_ANALYSIS_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("automation.analysis.timeout.seconds", "90")));
    public static final Duration DOCUMENT_REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("document.request.timeout.seconds", "240")));
    public static final Duration VISION_REQ_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("vision.request.timeout.seconds", "240")));
    public static final int VISION_MAX_ATTEMPTS = Integer.parseInt(
            loadProperty("vision.max_attempts", "2"));
    /** AI 自由生成脚本存在主机执行风险，默认关闭，仅保留预置模板执行。 */
    public static final boolean DOCUMENT_FREE_SCRIPT_ENABLED =
            Boolean.parseBoolean(loadProperty("document.free_script.enabled", "false"));
    public static final Duration DOCUMENT_SCRIPT_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("document.script.timeout.seconds", "45")));
    public static final boolean DATABASE_ENABLED =
            Boolean.parseBoolean(loadProperty("database.enabled", "false"));
    public static final String DATABASE_URL = loadProperty("database.url",
            "jdbc:mysql://127.0.0.1:3306/ilink_bot?useUnicode=true&characterEncoding=UTF-8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=5000&socketTimeout=10000");
    public static final String DATABASE_USERNAME = loadProperty("database.username", "root");
    public static final String DATABASE_PASSWORD = loadProperty("database.password", "");
    public static final String DATABASE_BOT_ID = loadProperty("database.bot.id", "ilink-bot-1");
    /** Personal Agent 的唯一使用者；为空时进程会锁定首位发消息的用户。 */
    public static final String PERSONAL_OWNER_USER_ID = loadProperty("personal.owner.user.id", "").trim();
    /** 高德 Web 服务 Key；为空时保留文字出行建议，不生成地图图片。 */
    public static final String AMAP_API_KEY = loadProperty("amap.api.key", "");
    /**embedding配置。*/
    public static final String EMBEDDING_API_URL = loadProperty("embedding.api.url",
            "https://api.siliconflow.cn/v1/embeddings");
    public static final String EMBEDDING_MODEL = loadProperty("embedding.model", "BAAI/bge-large-zh-v1.5");
    public static final int EMBEDDING_DIMENSION = Integer.parseInt(loadProperty("embedding.dimension", "1024"));
    public static final double RAG_MIN_SCORE = Double.parseDouble(loadProperty("rag.min.score", "0.55"));
    /** 百度地图 AK，用于快递 H5 和服务端出行规划。 */
    public static final String BAIDU_MAP_AK = loadProperty("baidu.map.ak", "");
    /** 滴滴 MCP Key；优先从环境变量 DIDI_MCP_KEY 读取，避免把密钥写入配置文件。 */
    public static final String DIDI_MCP_KEY = loadSecretProperty("DIDI_MCP_KEY", "didi.mcp.key");
    /** 是否使用滴滴 MCP 调试环境；生产环境默认关闭。 */
    public static final boolean DIDI_MCP_SANDBOX =
            Boolean.parseBoolean(loadProperty("didi.mcp.sandbox", "false"));
    /** 通用 MCP 服务地址；留空时只保留已有的专用 MCP 适配器。 */
    public static final String MCP_SERVER_URL = loadProperty("mcp.server.url", "");
    public static final String MCP_SERVER_AUTH = loadSecretProperty("MCP_SERVER_AUTH", "mcp.server.auth");
    /** 快递 H5 页面服务端口。 */
    public static final int EXPRESS_PORT = Integer.parseInt(loadProperty("express.port", "8089"));
    /** 快递 H5 页面公网地址；为空时使用本机地址。 */
    public static final String EXPRESS_BASE_URL = loadProperty("express.base-url", "");
    public static final boolean EXPRESS_TUNNEL_ENABLED =
            Boolean.parseBoolean(loadProperty("express.tunnel.enabled", "false"));
    public static final String EXPRESS_TUNNEL_COMMAND =
            loadProperty("express.tunnel.command", "data/tools/cloudflared.exe");
    public static final Duration EXPRESS_TUNNEL_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("express.tunnel.timeout.seconds", "25")));
    /** 手机定位授权页；浏览器定位需要 HTTPS，因此默认尝试启动 Cloudflare 临时隧道。 */
    public static final boolean LOCATION_ENABLED =
            Boolean.parseBoolean(loadProperty("location.enabled", "true"));
    public static final String LOCATION_BIND_ADDRESS =
            loadProperty("location.bind.address", "127.0.0.1");
    public static final int LOCATION_PORT = Integer.parseInt(loadProperty("location.port", "8792"));
    public static final String LOCATION_BASE_URL = loadProperty("location.base-url", "");
    public static final boolean LOCATION_TUNNEL_ENABLED =
            Boolean.parseBoolean(loadProperty("location.tunnel.enabled", "true"));
    public static final String LOCATION_TUNNEL_COMMAND =
            loadProperty("location.tunnel.command", "data/tools/cloudflared.exe");
    public static final Duration LOCATION_TUNNEL_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("location.tunnel.timeout.seconds", "25")));
    public static final Duration LOCATION_TOKEN_TTL = Duration.ofSeconds(Math.max(60,
            Long.parseLong(loadProperty("location.token.ttl.seconds", "300"))));
    public static final boolean LOGIN_BRIEFING_ENABLED =
            Boolean.parseBoolean(loadProperty("briefing.login.enabled", "true"));
    public static final boolean BRIEFING_POLISH_ENABLED =
            Boolean.parseBoolean(loadProperty("briefing.polish.enabled", "false"));
    public static final Duration BRIEFING_POLISH_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("briefing.polish.timeout.seconds", "60")));
    public static final String BRIEFING_DEFAULT_LOCATION = loadProperty("briefing.default.location", "");
    public static final boolean BRIEFING_NEWS_ENABLED =
            Boolean.parseBoolean(loadProperty("briefing.news.enabled", "false"));
    public static final boolean DAILY_DASHBOARD_ENABLED =
            Boolean.parseBoolean(loadProperty("dashboard.enabled", "true"));
    public static final String DAILY_DASHBOARD_BIND_ADDRESS =
            loadProperty("dashboard.bind.address", "127.0.0.1");
    public static final int DAILY_DASHBOARD_PORT =
            Integer.parseInt(loadProperty("dashboard.port", "8787"));
    public static final String DAILY_DASHBOARD_PUBLIC_URL =
            loadProperty("dashboard.public.url", "");
    public static final boolean DAILY_DASHBOARD_TUNNEL_ENABLED =
            Boolean.parseBoolean(loadProperty("dashboard.tunnel.enabled", "false"));
    public static final String DAILY_DASHBOARD_TUNNEL_COMMAND =
            loadProperty("dashboard.tunnel.command", "data/tools/cloudflared.exe");
    public static final Duration DAILY_DASHBOARD_TUNNEL_TIMEOUT = Duration.ofSeconds(
            Long.parseLong(loadProperty("dashboard.tunnel.timeout.seconds", "25")));
    /** 仅本机使用的会话管理页面。 */
    public static final boolean SESSION_MANAGEMENT_ENABLED =
            Boolean.parseBoolean(loadProperty("session.management.enabled", "true"));
    public static final String SESSION_MANAGEMENT_BIND_ADDRESS =
            loadProperty("session.management.bind.address", "127.0.0.1");
    public static final int SESSION_MANAGEMENT_PORT =
            Integer.parseInt(loadProperty("session.management.port", "8791"));
    /** 本机 Web Bot 配置，与微信入站适配器相互独立。 */
    public static final boolean WEB_CHAT_ENABLED =
            Boolean.parseBoolean(loadProperty("web.chat.enabled", "true"));
    public static final String WEB_CHAT_BIND_ADDRESS =
            loadProperty("web.chat.bind.address", "127.0.0.1");
    public static final int WEB_CHAT_PORT =
            Integer.parseInt(loadProperty("web.chat.port", "8793"));
    public static final long WEB_CHAT_MAX_UPLOAD_BYTES = Math.max(1L, Long.parseLong(
            loadProperty("web.chat.max.upload.mb", "25"))) * 1024L * 1024L;
    /** Roots exposed by the local Web workspace browser; paths outside these roots are rejected. */
    public static final List<Path> WORKSPACE_ROOTS = Arrays.stream(
                    loadProperty("workspace.roots", "").split(";"))
            .map(String::trim).filter(value -> !value.isBlank()).map(Path::of).toList();
    public static final long WORKSPACE_MAX_SEND_BYTES = Math.max(1L, Long.parseLong(
            loadProperty("workspace.max.send.mb", "20"))) * 1024L * 1024L;
    /** Executive Automation 本机控制台。 */
    public static final boolean AUTOMATION_CONSOLE_ENABLED =
            Boolean.parseBoolean(loadProperty("automation.console.enabled", "true"));
    public static final String AUTOMATION_CONSOLE_BIND_ADDRESS =
            loadProperty("automation.console.bind.address", "127.0.0.1");
    public static final int AUTOMATION_CONSOLE_PORT =
            Integer.parseInt(loadProperty("automation.console.port", "8790"));
    public static final boolean VISUAL_CARDS_ENABLED =
            Boolean.parseBoolean(loadProperty("visual.cards.enabled", "true"));
    public static final String VISUAL_CARDS_MODE = loadProperty("visual.cards.mode", "image");
    public static final int VISUAL_CARDS_MAX_DECK_SIZE = Math.max(1, Math.min(6,
            Integer.parseInt(loadProperty("visual.cards.max.deck.size", "6"))));
    public static final boolean VISUAL_LINK_QRCODE =
            Boolean.parseBoolean(loadProperty("visual.cards.link.qrcode", "true"));
    public static final String TAVILY_API_KEY = loadProperty("web.search.tavily.api.key", "");
    public static final int WEB_SEARCH_TIMEOUT_SECONDS =
            Integer.parseInt(loadProperty("web.search.timeout.seconds", "20"));
    public static final int WEB_SEARCH_RESULT_LIMIT =
            Integer.parseInt(loadProperty("web.search.result.limit", "5"));
    public static final boolean INTEREST_RADAR_ENABLED =
            Boolean.parseBoolean(loadProperty("radar.enabled", "true"));
    public static final int INTEREST_RADAR_POLL_MINUTES = Math.max(5,
            Integer.parseInt(loadProperty("radar.poll.minutes", "30")));
    public static final int INTEREST_RADAR_MIN_SCORE = Math.max(0, Math.min(100,
            Integer.parseInt(loadProperty("radar.min.score", "60"))));
    public static final int INTEREST_RADAR_VIDEO_PUSH_HOURS = Math.max(1,
            Integer.parseInt(loadProperty("radar.video.push.hours", "3")));
    public static final int INTEREST_RADAR_DIGEST_HOURS = Math.max(1,
            Integer.parseInt(loadProperty("radar.digest.hours", "3")));
    public static final int INTEREST_RADAR_DIGEST_MAX_ITEMS = Math.max(1, Math.min(5,
            Integer.parseInt(loadProperty("radar.digest.max.items", "3"))));
    public static final int INTEREST_RADAR_DAILY_MAX_PUSHES = Math.max(1, Math.min(24,
            Integer.parseInt(loadProperty("radar.daily.max.pushes", "8"))));
    public static final String INTEREST_RADAR_QUIET_START =
            loadProperty("radar.quiet.start", "23:00");
    public static final String INTEREST_RADAR_QUIET_END =
            loadProperty("radar.quiet.end", "08:00");
    public static final Path SDK_RESUME_CONTEXT_FILE =
            resolveConfiguredPath(loadProperty("sdk.resume.context.file", "data/sdk-resume-context.json"));
    public static final String KUAIDI100_CUSTOMER = loadProperty("kuaidi100.customer", "");
    public static final String KUAIDI100_KEY = loadProperty("kuaidi100.key", "");
    public static final String BANGUMI_API_BASE = loadProperty("bangumi.api.base", "https://api.bgm.tv");
    public static final String MUSICBRAINZ_API_BASE = loadProperty(
            "musicbrainz.api.base", "https://musicbrainz.org/ws/2");
    public static final String MUSICBRAINZ_USER_AGENT = loadProperty(
            "musicbrainz.user.agent", "ilink-bot/1.0");
    public static final String LRCLIB_API_BASE = loadProperty("lrclib.api.base", "https://lrclib.net/api");
    public static final boolean QQ_MAIL_ENABLED = Boolean.parseBoolean(
            loadProperty("qq.mail.enabled", "false"));
    public static final String QQ_MAIL_ADDRESS = loadProperty("qq.mail.address", "");
    public static final String QQ_MAIL_AUTH_CODE = loadProperty("qq.mail.auth.code", "");
    public static final String QQ_MAIL_OWNER_USER_ID = loadProperty("qq.mail.owner.user.id", "");
    public static final String QQ_MAIL_IMAP_HOST = loadProperty("qq.mail.imap.host", "imap.qq.com");
    public static final int QQ_MAIL_IMAP_PORT = Integer.parseInt(loadProperty("qq.mail.imap.port", "993"));

    /** 读取 API Key；不存在或仍为模板值时返回空字符串，由启动入口统一校验。 */
    private static String loadApiKey() {
        try {
            Properties props = new Properties();
            Path path = configPath();
            if (path != null) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                    String key = props.getProperty("api.key");
                    if (key != null && !key.isBlank() && !key.contains("把你的key")) {
                        return key;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** 读取普通配置项，文件不存在或读取失败时返回默认值。 */
    private static String loadProperty(String name, String defaultValue) {
        try {
            Properties props = new Properties();
            Path path = configPath();
            if (path != null) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            }
            return props.getProperty(name, defaultValue);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 支持从项目目录启动时读取项目上一级的私有配置文件。 */
    private static Path configPath() {
        return CONFIG_PATH;
    }

    private static Path locateConfigPath() {
        Path directory = Path.of("").toAbsolutePath().normalize();
        for (int level = 0; level < 4 && directory != null; level++) {
            Path candidate = directory.resolve("config.properties");
            if (Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        return null;
    }

    private static Path resolveConfiguredPath(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) return path.normalize();
        Path base = CONFIG_PATH == null
                ? Path.of("").toAbsolutePath().normalize()
                : CONFIG_PATH.getParent();
        return base.resolve(path).normalize();
    }

    private static String loadSecretProperty(String environmentName, String propertyName) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) return environmentValue.trim();
        String propertyValue = loadProperty(propertyName, "");
        return propertyValue.isBlank() ? loadProperty(environmentName, "") : propertyValue;
    }
}

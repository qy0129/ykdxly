package com.example.ilink.application.messaging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.PrintStream;
import java.util.regex.Pattern;

/** 控制台日志的唯一格式入口，保证项目自身日志使用中文且保留请求上下文。 */
public final class ConsoleLog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int SUMMARY_LIMIT = 240;
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static final Pattern SENSITIVE_JSON_VALUE = Pattern.compile(
            "(?i)(\\\"(?:api[_-]?key|access[_-]?token|token|password|secret|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*\\\"");

    static {
        installLegacyBridge();
    }

    private ConsoleLog() {
    }

    /**
     * 兼容尚未迁移的旧 System.out/err 调用，防止控制台同时出现两套格式。
     * 新代码始终应调用本类的 info、warn、error 方法。
     */
    public static synchronized void installLegacyBridge() {
        if (System.out instanceof LegacyPrintStream) return;
        System.setOut(new LegacyPrintStream(ORIGINAL_OUT, false));
        System.setErr(new LegacyPrintStream(ORIGINAL_ERR, true));
    }

    public static void info(String event, String message) {
        write("信息", event, message, false);
    }

    public static void warn(String event, String message) {
        write("警告", event, message, true);
    }

    public static void error(String event, String message) {
        write("错误", event, message, true);
    }

    /** 输出完整聊天文本，供演示和排查时还原实际对话。 */
    public static void userMessage(String userId, String text) {
        conversation("用户", userId, text);
    }

    public static void userMessage(ChannelType channel, String userId, String text) {
        conversation(channel, "用户", userId, text);
    }

    /** 输出成功发送给用户的完整 Bot 文本。 */
    public static void botMessage(String userId, String text) {
        conversation("机器人", userId, text);
    }

    public static void botMessage(ChannelType channel, String userId, String text) {
        conversation(channel, "机器人", userId, text);
    }

    /** 工具和 Skill 日志使用该方法生成参数、结果摘要，避免泄露敏感数据。 */
    public static String summary(String value) {
        if (value == null || value.isBlank()) return "（无）";
        String redacted = SENSITIVE_JSON_VALUE.matcher(value).replaceAll("$1***\\\"");
        String compact = redacted.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() <= SUMMARY_LIMIT ? compact : compact.substring(0, SUMMARY_LIMIT) + "...";
    }

    public static String errorSummary(Throwable error) {
        if (error == null) return "未知错误";
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        return message == null || message.isBlank() ? type : type + "，原始原因：" + summary(message);
    }

    private static void conversation(String speaker, String userId, String text) {
        String content = text == null || text.isBlank() ? "（空内容）" : text.strip();
        write("对话", speaker + "消息", "用户标识=" + safeUserId(userId) + "，内容：\n" + content.indent(2), false);
    }

    private static void conversation(ChannelType channel, String speaker, String userId, String text) {
        String content = text == null || text.isBlank() ? "（空内容）" : text.strip();
        writeWithPrefix(RequestLogContext.prefix(channel, speaker + "消息", userId, "", ""),
                "对话", "用户标识=" + safeUserId(userId) + "，内容：\n" + content.indent(2), false);
    }

    private static void write(String level, String event, String message, boolean error) {
        writeWithPrefix(RequestLogContext.prefix(event), level, message, error);
    }

    private static void writeWithPrefix(String prefix, String level, String message, boolean error) {
        String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "][" + level + "]"
                + prefix + " " + (message == null ? "（无内容）" : message);
        if (error) ORIGINAL_ERR.println(line);
        else ORIGINAL_OUT.println(line);
    }

    private static String safeUserId(String userId) {
        return userId == null || userId.isBlank() ? "（未知）" : userId.replaceAll("[\\r\\n\\t ]+", " ").trim();
    }

    private static String localizeLegacy(String value) {
        if (value == null || value.isBlank()) return "（空日志）";
        String localized = value
                .replace("[Database]", "[数据库]")
                .replace("[TTS]", "[语音合成]")
                .replace("[Image]", "[图片]")
                .replace("[Audio]", "[语音]")
                .replace("[Document]", "[文档]")
                .replace("[Performance]", "[性能统计]")
                .replace("[Automation]", "[自动化]")
                .replace("[Automation ", "[自动化 ")
                .replace("[RAG]", "[知识库]")
                .replace("[SYS]", "[系统]")
                .replace("[WX]", "[微信]")
                .replace("[W]", "[网页]")
                .replace("server started", "服务已启动")
                .replace("status=", "状态=")
                .replace("error=", "错误=")
                .replace("elapsed_ms=", "耗时毫秒=")
                .replace("message_ms=", "消息耗时毫秒=")
                .replace("request_chars=", "请求字符数=")
                .replace("chars=", "字符数=")
                .replace("bytes=", "字节数=")
                .replace("preview=", "摘要=")
                .replace("input=", "输入=")
                .replace("output=", "输出=")
                .replace("file=", "文件=")
                .replace("user=", "用户标识=")
                .replace("request=", "请求=")
                .replace("model=", "模型=");
        localized = localized
                .replace("primary unavailable", "主查询不可用")
                .replace("secondary unavailable", "备用查询不可用")
                .replace("request timed out", "请求超时")
                .replace("offline", "离线")
                .replace("timeout", "超时")
                .replace("items=", "数量=")
                .replace("count=", "数量=")
                .replace("success", "成功")
                .replace("failed", "失败")
                .replace("running", "执行中")
                .replace("unknown", "未知")
                .replace("error", "错误")
                .replace("Google News", "Google 新闻")
                .replace("kind=", "类型=")
                .replace("label=", "名称=")
                .replace("source=", "来源=")
                .replace("phase=", "阶段=")
                .replace("message_id=", "消息标识=")
                .replace("bot_id=", "机器人编号=")
                .replace("port=", "端口=");
        return localized;
    }

    private static final class LegacyPrintStream extends PrintStream {
        private final PrintStream delegate;
        private final boolean error;

        private LegacyPrintStream(PrintStream delegate, boolean error) {
            super(delegate, true);
            this.delegate = delegate;
            this.error = error;
        }

        @Override
        public void println(String value) {
            String line = "[" + LocalDateTime.now().format(TIME_FORMAT) + "][" + (error ? "错误" : "信息")
                    + "][系统][兼容日志] " + localizeLegacy(value);
            delegate.println(line);
        }

        @Override
        public void println(Object value) {
            println(String.valueOf(value));
        }
    }
}

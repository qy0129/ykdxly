package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

@Service
public class DashScopeService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeService.class);

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private static final Pattern WEATHER_PATTERN = Pattern.compile(
            ".*(天气|气温|温度|下雨|下雪|刮风|冷不冷|热不热|多少度).*");

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String apiUrl;
    private final String modelName;
    private final String visionModelName;
    private final String visionApiUrl;
    private final String audioModelName;
    private final String documentModelName;
    private final double videoFps;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String FILES_API_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/files";
    private static final String WANXIANG_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis";
    private static final String TASK_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/tasks";
    private static final int VIDEO_BASE64_MAX_BYTES = 7 * 1024 * 1024;

    @Autowired(required = false)
    private WeatherService weatherService;

    public DashScopeService(
            @Value("${dashscope.api.key}") String apiKey,
            @Value("${dashscope.api.url}") String apiUrl,
            @Value("${dashscope.model.name}") String modelName,
            @Value("${dashscope.vision.model.name:qwen-vl-plus}") String visionModelName,
            @Value("${dashscope.vision.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}") String visionApiUrl,
            @Value("${dashscope.audio.model.name:qwen2.5-audio-7b-instruct}") String audioModelName,
            @Value("${dashscope.document.model.name:qwen-long}") String documentModelName,
            @Value("${dashscope.video.fps:2}") double videoFps) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        this.modelName = modelName;
        this.visionModelName = visionModelName;
        this.visionApiUrl = visionApiUrl;
        this.audioModelName = audioModelName;
        this.documentModelName = documentModelName;
        this.videoFps = videoFps;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public String chat(String userMessage, List<String[]> history) {
        return chat(userMessage, history, null);
    }

    public String chat(String userMessage, List<String[]> history, String tone) {
        String weatherInfo = null;
        if (weatherService != null && WEATHER_PATTERN.matcher(userMessage).matches()) {
            weatherInfo = weatherService.detectAndGetWeather(userMessage);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode systemMsg = mapper.createObjectNode();
        String toneGuide = "";
        if (tone != null && !tone.isBlank()) {
            toneGuide = switch (tone) {
                case "正式" -> "请使用正式、严谨的书面语气回答问题，措辞规范，避免口语化表达。";
                case "可爱" -> "请用可爱、亲切的语气回答问题，可以适当使用语气词，让回复显得活泼温暖。";
                case "专业" -> "请用专业、精准的技术语言回答，引用具体概念和数据，体现专业性。";
                case "朋友" -> "请像朋友聊天一样自然地回答问题，语气随意亲切，拉近距离。";
                case "幽默" -> "请用幽默风趣的语气回答问题，适当加入俏皮话，让回复轻松有趣。";
                case "温柔" -> "请用温柔、善解人意的语气回答问题，多使用关怀和安慰的措辞。";
                case "诗意" -> "请用富有诗意和文艺气息的语言回答问题，适当运用修辞手法。";
                case "简洁" -> "请用最简洁的语言回答问题，直接给出核心信息，不要多余修饰。";
                case "热情" -> "请用热情洋溢、充满活力的语气回答问题，让回复具有感染力。";
                default -> "请用友好、自然的语气回答问题。";
            };
        }
        String systemPrompt = "你是一个智能助手，请用中文回答问题。结合对话历史理解上下文，回答一般不超过200字。不要使用表情符号。如果你不知道答案，不要编造，直接说不知道。"
                + (toneGuide.isEmpty() ? "请用友好自然的语气回答问题。" : "\n\n当前语气要求：" + toneGuide);
        if (weatherInfo != null) {
            systemPrompt += "\n\n当前实时天气数据：" + weatherInfo + "\n用户询问天气时，请基于以上真实数据回答。";
        }
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        if (history != null) {
            for (String[] turn : history) {
                ObjectNode userTurn = mapper.createObjectNode();
                userTurn.put("role", "user");
                userTurn.put("content", turn[0]);
                messages.add(userTurn);

                ObjectNode assistantTurn = mapper.createObjectNode();
                assistantTurn.put("role", "assistant");
                assistantTurn.put("content", turn[1]);
                messages.add(assistantTurn);
            }
        }

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        ObjectNode input = mapper.createObjectNode();
        input.set("messages", messages);
        body.set("input", input);

        ObjectNode params = mapper.createObjectNode();
        params.put("result_format", "text");
        body.set("parameters", params);

        return doRequest(body);
    }

    /**
     * 文生图：调用万相-文生图V2版
     * 使用异步任务模式（wanx2.1-t2i-plus），轮询获取结果
     */
    public String textToImage(String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "wanx2.1-t2i-plus");
        ObjectNode input = mapper.createObjectNode();
        input.put("prompt", prompt);
        body.set("input", input);
        ObjectNode params = mapper.createObjectNode();
        params.put("size", "768*768");
        params.put("n", 1);
        body.set("parameters", params);

        try {
            String json = body.toString();
            Request request = new Request.Builder()
                    .url(WANXIANG_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            String respBody;
            try (Response response = httpClient.newCall(request).execute()) {
                respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("万相创建任务失败: status={}, body={}", response.code(), respBody);
                    return null;
                }
            }

            JsonNode root = mapper.readTree(respBody);
            String taskId = root.path("output").path("task_id").asText();
            if (taskId.isBlank()) {
                log.warn("万相响应缺少 task_id: {}", respBody);
                return null;
            }
            log.info("万相任务已创建: taskId={}", taskId);

            // 轮询任务结果（最多 120 秒）
            for (int i = 0; i < 60; i++) {
                Thread.sleep(2000);
                String pollResp = doHttpGet(TASK_API_URL + "/" + taskId);
                if (pollResp == null) continue;

                JsonNode pollOutput = mapper.readTree(pollResp).get("output");
                if (pollOutput == null) continue;

                String status = pollOutput.path("task_status").asText();
                log.info("万相任务状态: {}", status);

                if ("SUCCEEDED".equals(status)) {
                    log.info("万相任务成功响应: {}", pollResp);

                    // 尝试多种字段路径：results[].image_url, results[].url, output.image_url, data[].url
                    JsonNode results = pollOutput.get("results");
                    if (results != null && results.isArray()) {
                        for (JsonNode r : results) {
                            String url = r.path("image_url").asText();
                            if (url.isBlank()) url = r.path("url").asText();
                            if (!url.isBlank()) {
                                log.info("万相文生图成功: {}", url);
                                return url;
                            }
                        }
                    }
                    String directUrl = pollOutput.path("image_url").asText();
                    if (directUrl.isBlank()) directUrl = pollOutput.path("url").asText();
                    if (!directUrl.isBlank()) {
                        log.info("万相文生图成功: {}", directUrl);
                        return directUrl;
                    }
                    log.warn("万相任务成功但无法提取图片 URL");
                    return null;
                }
                if ("FAILED".equals(status)) {
                    log.warn("万相任务失败: {}", pollResp);
                    return null;
                }
            }
            log.warn("万相任务超时: taskId={}", taskId);
        } catch (Exception e) {
            log.warn("万相文生图异常: {}", e.getMessage());
        }
        return null;
    }

    private static final String MULTIMODAL_API_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    public String transcribeAudio(byte[] audioBytes) {
        String[] modelsToTry;
        if (audioModelName != null && !audioModelName.isBlank()) {
            modelsToTry = new String[]{audioModelName, "qwen3.5-omni-plus", "qwen2.5-audio-7b-instruct", "qwen2-audio-7b-instruct", "qwen-audio"};
        } else {
            modelsToTry = new String[]{"qwen3.5-omni-plus", "qwen2.5-audio-7b-instruct", "qwen2-audio-7b-instruct", "qwen-audio"};
        }

        // 检测音频格式头部
        StringBuilder hexHeader = new StringBuilder();
        for (int i = 0; i < Math.min(20, audioBytes.length); i++) {
            hexHeader.append(String.format("%02x ", audioBytes[i] & 0xff));
        }
        int offset = (audioBytes.length > 0 && audioBytes[0] == 0x02) ? 1 : 0;
        boolean isSilk = audioBytes.length > offset + 8
            && audioBytes[offset] == '#' && audioBytes[offset + 1] == '!'
            && audioBytes[offset + 2] == 'S' && audioBytes[offset + 3] == 'I'
            && audioBytes[offset + 4] == 'L' && audioBytes[offset + 5] == 'K'
            && audioBytes[offset + 6] == '_' && audioBytes[offset + 7] == 'V';
        String fmt = isSilk ? "Silk" : (audioBytes.length > 0 ? "未知" : "空");
        log.info("音频格式: {}, 长度: {} bytes, 前20字节 hex: {}", fmt, audioBytes.length, hexHeader);

        // 去除 0x02 前缀后的音频数据
        byte[] cleanAudio = audioBytes;
        if (audioBytes.length > 0 && audioBytes[0] == 0x02) {
            cleanAudio = Arrays.copyOfRange(audioBytes, 1, audioBytes.length);
        }

        // 尝试 Silk → WAV 解码（通过 Python pysilk）
        byte[] wavBytes = null;
        if (isSilk) {
            wavBytes = decodeSilkToWav(cleanAudio);
            if (wavBytes != null) {
                log.info("Silk→WAV 转码成功: {} bytes", wavBytes.length);
            }
        }

        // 如果有 WAV 数据，优先用 qwen3.5-omni-plus 识别（此前 Silk 直传返回"audio is empty"）
        if (wavBytes != null) {
            String wavBase64 = Base64.getEncoder().encodeToString(wavBytes);
            String wavDataUri = "data:audio/wav;base64," + wavBase64;
            for (String model : new String[]{audioModelName, "qwen3.5-omni-plus", "qwen3.5-omni-plus-realtime"}) {
                if (model == null || model.isBlank()) continue;
                log.info("尝试语音识别(WAV转码) OpenAI格式: {}", model);
                String result = tryAudioOpenAI(model, wavDataUri);
                if (result != null) return result;
                log.info("尝试语音识别(WAV转码) DashScope格式: {}", model);
                result = tryAudioDashScope(model, wavDataUri);
                if (result != null) return result;
            }
            log.info("WAV 转码后 qwen3.5-omni-plus 仍不可用，继续尝试原始 Silk 方案");
        }

        String base64 = Base64.getEncoder().encodeToString(audioBytes);
        String dataUri = "data:audio/silk;base64," + base64;

        // 方案1: 通过 Qwen-Audio 多模态模型转写（支持多种音频格式）
        for (String model : modelsToTry) {
            log.info("尝试语音识别模型(OpenAI格式): {}", model);
            String result = tryAudioOpenAI(model, dataUri);
            if (result != null) return result;

            log.info("尝试语音识别模型(DashScope格式): {}", model);
            result = tryAudioDashScope(model, dataUri);
            if (result != null) return result;
        }

        // 方案2: 尝试不同 MIME 类型（某些模型可能自动检测真实音频格式）
        log.info("尝试不同 MIME 类型重试");
        String mimeFallback = tryAudioOpenAIWithWavFallback(audioBytes);
        if (mimeFallback != null) return mimeFallback;

        // 方案3: 通过 OpenAI 兼容的音频转录端点上传文件转写
        log.info("尝试 OpenAI 音频转录端点");
        String openaiResult = tryOpenAIAudioTranscription(audioBytes);
        if (openaiResult != null) return openaiResult;

        // 方案4: 尝试通过 DashScope 直接调用 paraformer-v2（使用 data URI 替代文件 URL）
        log.info("尝试 paraformer-v2(data URI方式)");
        String paraDirectResult = tryParaformerDirect(audioBytes);
        if (paraDirectResult != null) return paraDirectResult;

        // 方案5: 通过 DashScope 文件上传 + paraformer-v2 转录
        log.info("尝试 DashScope 文件上传 + paraformer-v2");
        String uploadResult = tryParaformerWithDashScopeUpload(audioBytes);
        if (uploadResult != null) return uploadResult;

        // 方案6: 通过外部文件托管 + Paraformer 文件转录 API
        log.info("尝试 Paraformer 文件转录(外部托管)");
        String paraformerResult = tryParaformerFileTranscription(audioBytes);
        if (paraformerResult != null) return paraformerResult;

        // 方案7: paraformer-realtime-v2 WebSocket 实时转写（直接传音频数据，无需公网 URL）
        log.info("尝试 paraformer-realtime-v2 WebSocket 实时转写");
        String wsResult = tryParaformerRealtime(audioBytes);
        if (wsResult != null) return wsResult;

        log.warn("所有语音识别方案均不可用");
        return null;
    }

    private String tryAudioOpenAIWithWavFallback(byte[] audioBytes) {
        // 尝试将音频伪装成 WAV 格式发送（某些模型可能自动检测格式）
        String base64 = Base64.getEncoder().encodeToString(audioBytes);
        String[] mimeTypes = {"audio/wav", "audio/mp3", "audio/ogg", "audio/mpeg", "audio/x-wav"};
        String[] models = {audioModelName, "qwen2.5-audio-7b-instruct", "qwen2-audio-7b-instruct", "qwen-audio"};
        for (String mime : mimeTypes) {
            String dataUri = "data:" + mime + ";base64," + base64;
            for (String model : models) {
                if (model == null || model.isBlank()) continue;
                log.info("尝试多格式语音: mime={}, model={}", mime, model);
                String result = tryAudioOpenAI(model, dataUri);
                if (result != null) return result;
                result = tryAudioDashScope(model, dataUri);
                if (result != null) return result;
            }
        }
        return null;
    }

    private String tryAudioOpenAI(String modelName, String dataUri) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode contentArray = mapper.createArrayNode();

        ObjectNode audioNode = mapper.createObjectNode();
        audioNode.put("type", "audio_url");
        ObjectNode audioUrlNode = mapper.createObjectNode();
        audioUrlNode.put("url", dataUri);
        audioNode.set("audio_url", audioUrlNode);
        contentArray.add(audioNode);

        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", "请转写这段语音内容，仅返回转写结果。");
        contentArray.add(textNode);

        userMsg.set("content", contentArray);
        messages.add(userMsg);
        body.set("messages", messages);

        try {
            String respBody = doHttpPost(visionApiUrl, body.toString());
            if (respBody == null) return null;
            String result = parseTextReply(respBody);
            if (result == null) log.warn("语音模型 {} OpenAI格式: 无法解析响应: {}", modelName, respBody);
            return result;
        } catch (Exception e) {
            log.warn("语音模型 {} OpenAI格式异常: {}", modelName, e.getMessage());
            return null;
        }
    }

    private String tryAudioDashScope(String modelName, String dataUri) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode contentArray = mapper.createArrayNode();

        ObjectNode audioNode = mapper.createObjectNode();
        audioNode.put("audio", dataUri);
        contentArray.add(audioNode);

        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("text", "请转写这段语音内容，仅返回转写结果。");
        contentArray.add(textNode);

        userMsg.set("content", contentArray);
        messages.add(userMsg);

        ObjectNode input = mapper.createObjectNode();
        input.set("messages", messages);
        body.set("input", input);

        ObjectNode params = mapper.createObjectNode();
        params.put("result_format", "text");
        body.set("parameters", params);

        try {
            String respBody = doHttpPost(MULTIMODAL_API_URL, body.toString());
            if (respBody == null) return null;
            String result = parseTextReply(respBody);
            if (result == null) log.warn("语音模型 {} DashScope格式: 无法解析响应: {}", modelName, respBody);
            return result;
        } catch (Exception e) {
            log.warn("语音模型 {} DashScope格式异常: {}", modelName, e.getMessage());
            return null;
        }
    }

    /**
     * 后备方案: 通过 OpenAI 兼容的 /v1/audio/transcriptions 端点直接上传音频文件转写
     */
    private String tryOpenAIAudioTranscription(byte[] audioBytes) {
        String url = visionApiUrl.replace("/chat/completions", "/audio/transcriptions");
        if (url.equals(visionApiUrl)) {
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1/audio/transcriptions";
        }
        String fileName = "audio.silk";

        // 检查是否为 Silk 格式（WeChat 语音消息的标准格式）
        boolean isSilk = audioBytes.length > 8
            && audioBytes[0] == '#' && audioBytes[1] == '!'
            && audioBytes[2] == 'S' && audioBytes[3] == 'I'
            && audioBytes[4] == 'L' && audioBytes[5] == 'K'
            && audioBytes[6] == '_' && audioBytes[7] == 'V';
        if (!isSilk) {
            fileName = "audio.wav";
        }

        String[] models = {"whisper-1", "paraformer-v2", "qwen2.5-audio-7b-instruct"};
        for (String model : models) {
            try {
                log.info("尝试 OpenAI 音频转录端点, model={}", model);
                RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                        RequestBody.create(audioBytes, MediaType.parse("audio/silk")))
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "text")
                    .build();

                Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(requestBody)
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful() && !respBody.isBlank()) {
                        log.info("OpenAI 音频转录端点成功, model={}, result={}", model, respBody);
                        return respBody.trim();
                    }
                    log.warn("OpenAI 音频转录端点 model={} 失败: status={}, body={}", model, response.code(), respBody);
                }
            } catch (Exception e) {
                log.warn("OpenAI 音频转录端点 model={} 异常: {}", model, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 后备方案2: 使用 DashScope 文件转录 API (Paraformer)，通过临时文件托管服务提供可下载 URL
     * 将音频上传到临时文件托管服务，获取公共 URL 后调用 Paraformer 文件转录 API
     */
    private String tryParaformerFileTranscription(byte[] audioBytes) {
        // 先尝试将音频保存到临时文件
        Path tempDir = null;
        Path tempFile = null;
        try {
            tempDir = Files.createTempDirectory("voice_");
            tempFile = tempDir.resolve("audio.silk");
            Files.write(tempFile, audioBytes);

            // 尝试上传到免费文件托管服务
            String publicUrl = uploadToTempHosting(tempFile);
            if (publicUrl == null) {
                log.warn("无法上传音频到临时托管服务");
                return null;
            }
            log.info("音频已上传到: {}", publicUrl);

            // 调用 Paraformer 文件转录 API
            return callParaformerTranscription(publicUrl);
        } catch (Exception e) {
            log.warn("Paraformer 文件转录失败: {}", e.getMessage());
            return null;
        } finally {
            // 清理临时文件
            try { if (tempFile != null) Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            try { if (tempDir != null) Files.deleteIfExists(tempDir); } catch (Exception ignored) {}
        }
    }

    private String uploadToTempHosting(Path filePath) {
        try {
            byte[] fileBytes = Files.readAllBytes(filePath);
            // 尝试多个临时文件托管服务（优先选国内可访问的）
            String url = tryUploadFileio(fileBytes);
            if (url != null) return url;
            url = tryUploadTmpfiles(fileBytes);
            if (url != null) return url;
            url = tryUploadCatbox(fileBytes);
            if (url != null) return url;
            url = tryUploadUguu(fileBytes);
            if (url != null) return url;
        } catch (Exception e) {
            log.warn("所有文件托管服务均失败: {}", e.getMessage());
        }
        return null;
    }

    private String tryUploadFileio(byte[] fileBytes) {
        try {
            RequestBody uploadBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.silk",
                    RequestBody.create(fileBytes, MediaType.parse("application/octet-stream")))
                .build();

            Request request = new Request.Builder()
                .url("https://file.io")
                .header("Accept", "application/json")
                .post(uploadBody)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    JsonNode root = mapper.readTree(respBody);
                    JsonNode link = root.get("link");
                    if (link != null && !link.asText().isBlank()) {
                        log.info("file.io 上传成功: {}", link.asText());
                        return link.asText();
                    }
                }
                log.warn("file.io 上传失败: status={}, body={}", response.code(), respBody);
            }
        } catch (Exception e) {
            log.debug("file.io 失败: {}", e.getMessage());
        }
        return null;
    }

    private String tryUploadTmpfiles(byte[] fileBytes) {
        try {
            RequestBody uploadBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.silk",
                    RequestBody.create(fileBytes, MediaType.parse("application/octet-stream")))
                .build();

            Request request = new Request.Builder()
                .url("https://tmpfiles.org/api/v1/upload")
                .post(uploadBody)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    JsonNode root = mapper.readTree(respBody);
                    JsonNode data = root.get("data");
                    if (data != null) {
                        JsonNode url = data.get("url");
                        if (url != null && !url.asText().isBlank()) {
                            log.info("tmpfiles.org 上传成功: {}", url.asText());
                            return url.asText();
                        }
                    }
                }
                log.warn("tmpfiles.org 上传失败: status={}, body={}", response.code(), respBody);
            }
        } catch (Exception e) {
            log.debug("tmpfiles.org 失败: {}", e.getMessage());
        }
        return null;
    }

    private String tryUploadCatbox(byte[] fileBytes) {
        try {
            String b64 = Base64.getEncoder().encodeToString(fileBytes);
            RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("reqtype", "fileupload")
                .addFormDataPart("fileToUpload", "audio.silk",
                    RequestBody.create(fileBytes, MediaType.parse("application/octet-stream")))
                .build();

            Request request = new Request.Builder()
                .url("https://catbox.moe/user/api.php")
                .post(body)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful() && respBody.startsWith("https://")) {
                    log.info("catbox.moe 上传成功: {}", respBody.trim());
                    return respBody.trim();
                }
                log.warn("catbox.moe 上传失败: status={}, body={}", response.code(), respBody);
            }
        } catch (Exception e) {
            log.debug("catbox.moe 失败: {}", e.getMessage());
        }
        return null;
    }

    private String tryUploadUguu(byte[] fileBytes) {
        try {
            RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.silk",
                    RequestBody.create(fileBytes, MediaType.parse("application/octet-stream")))
                .build();

            Request request = new Request.Builder()
                .url("https://uguu.se/upload")
                .header("Accept", "application/json")
                .post(body)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    JsonNode root = mapper.readTree(respBody);
                    JsonNode files = root.get("files");
                    if (files != null && files.isArray() && files.size() > 0) {
                        JsonNode url = files.get(0).get("url");
                        if (url != null && !url.asText().isBlank()) {
                            log.info("uguu.se 上传成功: {}", url.asText());
                            return url.asText();
                        }
                    }
                }
                log.warn("uguu.se 上传失败: status={}, body={}", response.code(), respBody);
            }
        } catch (Exception e) {
            log.debug("uguu.se 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 直接尝试通过 DashScope 文件转录 API 使用 data URI（绕过文件托管需求）
     */
    private String tryParaformerDirect(byte[] audioBytes) {
        String base64 = Base64.getEncoder().encodeToString(audioBytes);
        String dataUri = "data:audio/silk;base64," + base64;
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/audio/transcription/transcription";
        String[] models = {"fun-asr", "paraformer-v2", "paraformer"};
        for (String model : models) {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", model);
                ObjectNode input = mapper.createObjectNode();
                ArrayNode urlArr = mapper.createArrayNode();
                urlArr.add(dataUri);
                input.set("file_urls", urlArr);
                body.set("input", input);

                String requestBody = body.toString();
                Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JsonNode root = mapper.readTree(respBody);
                        JsonNode output = root.get("output");
                        if (output != null) {
                            JsonNode taskStatus = output.get("task_status");
                            if (taskStatus != null && "SUCCEEDED".equals(taskStatus.asText())) {
                                JsonNode results = output.get("results");
                                if (results != null && results.isArray() && results.size() > 0) {
                                    String text = results.get(0).get("transcription_text").asText();
                                    if (text != null && !text.isBlank()) return text;
                                }
                            } else if (taskStatus != null && "PENDING".equals(taskStatus.asText())) {
                                String taskId = output.get("task_id").asText();
                                return pollParaformerResult(apiUrl, taskId);
                            }
                        }
                    } else {
                        log.warn("paraformer-v2直接调用 model={} 失败: status={}, body={}", model, response.code(), respBody);
                    }
                }
            } catch (Exception e) {
                log.warn("paraformer-v2直接调用 model={} 异常: {}", model, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 方案5: 上传文件到 DashScope + 调用 paraformer-v2 转录
     */
    private String tryParaformerWithDashScopeUpload(byte[] audioBytes) {
        String fileUri = uploadFileToDashScope(audioBytes, "audio.silk");
        if (fileUri == null) {
            log.warn("DashScope 文件上传失败");
            return null;
        }
        // 可能返回了用 | 分隔的多个 URI 格式，逐个尝试
        String[] uris = fileUri.split("\\|");
        for (String uri : uris) {
            if (uri == null || uri.isBlank()) continue;
            log.info("尝试 DashScope 文件 URI: {}", uri);
            String result = callParaformerTranscription(uri.trim());
            if (result != null) return result;
        }
        return null;
    }

    /**
     * 将音频文件上传到 DashScope 文件服务，返回文件 URI
     */
    private String uploadFileToDashScope(byte[] fileBytes, String fileName) {
        String[] uploadUrls = {
            "https://dashscope.aliyuncs.com/api/v1/files",
            "https://dashscope.aliyuncs.com/api/v1/datasets/files/upload"
        };
        for (String uploadUrl : uploadUrls) {
            try {
                RequestBody uploadBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                        RequestBody.create(fileBytes, MediaType.parse("application/octet-stream")))
                    .addFormDataPart("purpose", "transcription")
                    .build();

                Request request = new Request.Builder()
                    .url(uploadUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .post(uploadBody)
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JsonNode root = mapper.readTree(respBody);

                        // 格式1: data.uploaded_files[0].file_id (DashScope 数据集文件上传API)
                        JsonNode data = root.get("data");
                        if (data != null && data.has("uploaded_files")) {
                            JsonNode uploadedFiles = data.get("uploaded_files");
                            if (uploadedFiles.isArray() && uploadedFiles.size() > 0) {
                                String fileId = uploadedFiles.get(0).get("file_id").asText();
                                log.info("DashScope 文件上传成功, file_id={}", fileId);
                                // 返回多种 URI 格式，调用方逐个尝试
                                return "dashscope://file/" + fileId + "|file://" + fileId + "|" + fileId;
                            }
                        }

                        // 格式2: data.fileUri
                        if (data != null) {
                            JsonNode fileUri = data.get("fileUri");
                            if (fileUri != null && !fileUri.asText().isBlank()) {
                                log.info("DashScope 文件上传成功, fileUri={}", fileUri.asText());
                                return fileUri.asText();
                            }
                        }

                        // 格式3: id (OpenAI兼容)
                        JsonNode id = root.get("id");
                        if (id != null && !id.asText().isBlank()) {
                            String fileId = id.asText();
                            log.info("DashScope 文件上传成功, id={}", fileId);
                            return "file://" + fileId;
                        }

                        log.warn("DashScope 文件上传响应格式异常: {}", respBody);
                    } else {
                        log.warn("DashScope 文件上传 {} 失败: status={}, body={}", uploadUrl, response.code(), respBody);
                    }
                }
            } catch (Exception e) {
                log.debug("DashScope 文件上传 {} 失败: {}", uploadUrl, e.getMessage());
            }
        }
        return null;
    }

    private String callParaformerTranscription(String fileUrl) {
        String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/audio/transcription/transcription";
        String[] models = {"fun-asr", "paraformer-v2", "paraformer"};
        for (String model : models) {
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", model);

                ObjectNode input = mapper.createObjectNode();
                ArrayNode urls = mapper.createArrayNode();
                urls.add(fileUrl);
                input.set("file_urls", urls);
                body.set("input", input);

                String requestBody = body.toString();
                Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, JSON_MEDIA))
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JsonNode root = mapper.readTree(respBody);
                        JsonNode output = root.get("output");
                        if (output != null) {
                            JsonNode taskStatus = output.get("task_status");
                            if (taskStatus != null && "SUCCEEDED".equals(taskStatus.asText())) {
                                JsonNode results = output.get("results");
                                if (results != null && results.isArray() && results.size() > 0) {
                                    JsonNode firstResult = results.get(0);
                                    JsonNode text = firstResult.get("transcription_text");
                                    if (text != null && !text.asText().isBlank()) {
                                        return text.asText();
                                    }
                                }
                            } else if (taskStatus != null && "PENDING".equals(taskStatus.asText())) {
                                // 异步任务，轮询获取结果
                                String taskId = output.get("task_id").asText();
                                return pollParaformerResult(apiUrl, taskId);
                            }
                        }
                    } else {
                        log.warn("Paraformer 转录 model={} 失败: status={}, body={}", model, response.code(), respBody);
                    }
                }
            } catch (Exception e) {
                log.warn("Paraformer 转录 model={} 异常: {}", model, e.getMessage());
            }
        }
        return null;
    }

    private String pollParaformerResult(String apiUrl, String taskId) {
        try {
            for (int i = 0; i < 10; i++) {
                Thread.sleep(1000);
                Request request = new Request.Builder()
                    .url(apiUrl + "?task_id=" + taskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .get()
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JsonNode root = mapper.readTree(respBody);
                        JsonNode output = root.get("output");
                        if (output != null) {
                            JsonNode taskStatus = output.get("task_status");
                            if ("SUCCEEDED".equals(taskStatus.asText())) {
                                JsonNode results = output.get("results");
                                if (results != null && results.isArray() && results.size() > 0) {
                                    JsonNode text = results.get(0).get("transcription_text");
                                    if (text != null && !text.asText().isBlank()) {
                                        return text.asText();
                                    }
                                }
                            } else if ("FAILED".equals(taskStatus.asText())) {
                                log.warn("Paraformer 转录任务失败: {}", respBody);
                                return null;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("轮询 Paraformer 结果异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 方案7: 通过 DashScope paraformer-realtime-v2 WebSocket 实时转写
     * 直接发送音频二进制数据，无需公网 URL
     */
    private String tryParaformerRealtime(byte[] audioBytes) {
        // 去除前导 0x02 字节（微信协议标识）
        byte[] silkData;
        if (audioBytes.length > 0 && audioBytes[0] == 0x02) {
            silkData = Arrays.copyOfRange(audioBytes, 1, audioBytes.length);
        } else {
            silkData = audioBytes;
        }

        String[] wsModels = {"fun-asr-realtime", "paraformer-realtime-v2"};
        for (String wsModel : wsModels) {
            String result = tryWsModel(wsModel, silkData);
            if (result != null) return result;
        }
        return null;
    }

    private String tryWsModel(String wsModel, byte[] silkData) {
        String wsUrl = "wss://dashscope.aliyuncs.com/api/v1/services/audio/transcription/realtime-transcription";

        final String[] result = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        final String taskId = UUID.randomUUID().toString();

        Request request = new Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer " + apiKey)
            .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                try {
                    ObjectNode startMsg = mapper.createObjectNode();
                    ObjectNode header = mapper.createObjectNode();
                    header.put("action", "start");
                    header.put("task_id", taskId);
                    header.put("streaming", "duplex");
                    startMsg.set("header", header);

                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("model", wsModel);
                    payload.put("task_group", "audio");
                    payload.put("task", "asr");
                    ObjectNode input = mapper.createObjectNode();
                    input.put("format", "silk");
                    input.put("sample_rate", 16000);
                    payload.set("input", input);
                    startMsg.set("payload", payload);

                    ws.send(startMsg.toString());
                    ws.send(ByteString.of(silkData));

                    ObjectNode stopMsg = mapper.createObjectNode();
                    ObjectNode stopHeader = mapper.createObjectNode();
                    stopHeader.put("action", "stop");
                    stopHeader.put("task_id", taskId);
                    stopMsg.set("header", stopHeader);
                    ws.send(stopMsg.toString());

                } catch (Exception e) {
                    log.warn("发送 WebSocket 消息失败: {}", e.getMessage());
                    latch.countDown();
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonNode root = mapper.readTree(text);
                    String action = root.path("header").path("action").asText();

                    if ("result".equals(action)) {
                        String textResult = root.path("payload").path("result").path("text").asText();
                        if (!textResult.isBlank()) {
                            result[0] = (result[0] != null ? result[0] : "") + textResult;
                        }
                    } else if ("completed".equals(action)) {
                        latch.countDown();
                    }
                } catch (Exception e) {
                    log.warn("解析 WebSocket 消息失败: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.warn("paraformer-realtime WebSocket 失败: {}", t.getMessage());
                latch.countDown();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("paraformer-realtime WebSocket 关闭: {} {}", code, reason);
                latch.countDown();
            }
        };

        httpClient.newWebSocket(request, listener);

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (result[0] != null && !result[0].isBlank()) {
            log.info("paraformer-realtime 转写成功: {}", result[0]);
            return result[0];
        }

        log.warn("paraformer-realtime 转写未返回结果");
        return null;
    }

    /**
     * 通过 Java silk-codec 将 Silk 音频解码为 WAV 格式
     */
    private byte[] decodeSilkToWav(byte[] silkBytes) {
        try {
            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
            io.github.kasukusakura.silkcodec.SilkCoder.decode(
                new ByteArrayInputStream(silkBytes), pcmOut);
            byte[] pcmData = pcmOut.toByteArray();
            if (pcmData.length == 0) {
                log.warn("Silk解码失败: PCM 数据为空");
                return null;
            }
            return withWavHeader(pcmData, 24000);
        } catch (Exception e) {
            log.warn("Silk解码异常: {}", e.getMessage());
        }
        return null;
    }

    private static byte[] withWavHeader(byte[] pcmData, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        ByteBuffer buf = ByteBuffer.allocate(44);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(fileSize);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);
        buf.put("data".getBytes());
        buf.putInt(dataSize);

        byte[] header = buf.array();
        byte[] wav = new byte[44 + dataSize];
        System.arraycopy(header, 0, wav, 0, 44);
        System.arraycopy(pcmData, 0, wav, 44, dataSize);
        return wav;
    }

    private String doHttpPost(String url, String jsonBody) {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .post(jsonBody != null ? RequestBody.create(jsonBody, JSON_MEDIA) : RequestBody.create(new byte[0]))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("HTTP POST 失败: url={}, status={}, body={}", url, response.code(), respBody);
                return null;
            }
            return respBody;
        } catch (IOException e) {
            log.warn("HTTP POST 异常: {}", e.getMessage());
            return null;
        }
    }

    private String doHttpGet(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("HTTP GET 失败: url={}, status={}, body={}", url, response.code(), respBody);
                return null;
            }
            return respBody;
        } catch (IOException e) {
            log.warn("HTTP GET 异常: {}", e.getMessage());
            return null;
        }
    }

    private boolean isApiErrorMessage(String text) {
        if (text == null || text.isBlank()) return true;
        String lower = text.toLowerCase();
        return lower.contains("cannot read")
            || lower.contains("does not support")
            || lower.contains("inform the user")
            || lower.contains("not support")
            || lower.contains("this model")
            || lower.startsWith("error:")
            || lower.startsWith("sorry,");
    }

    private boolean hasTopLevelError(JsonNode root) {
        JsonNode err = root.get("error");
        if (err != null && !err.isNull()) return true;
        JsonNode code = root.get("code");
        if (code != null && !code.isNull() && !"0".equals(code.asText())) return true;
        return false;
    }

    private String parseTextReply(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);
            if (hasTopLevelError(root)) {
                log.warn("语音转写响应包含顶层错误: {}", respBody);
                return null;
            }
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                return extractTextFromChoice(choices.get(0));
            }
            JsonNode output = root.get("output");
            if (output != null) {
                JsonNode text = output.get("text");
                if (text != null && !text.asText().isBlank()) {
                    String t = text.asText();
                    if (isApiErrorMessage(t)) {
                        log.warn("语音转写 output 为错误消息: {}", t);
                        return null;
                    }
                    return t;
                }
                JsonNode outChoices = output.get("choices");
                if (outChoices != null && outChoices.isArray() && outChoices.size() > 0) {
                    return extractTextFromChoice(outChoices.get(0));
                }
            }
        } catch (Exception e) {
            log.error("解析语音转写响应失败: {}", e.getMessage());
        }
        return null;
    }

    private String extractTextFromChoice(JsonNode choice) {
        JsonNode message = choice.get("message");
        if (message == null) return null;
        JsonNode content = message.get("content");
        if (content == null) return null;
        if (content.isTextual()) {
            String text = content.asText();
            if (!text.isBlank() && !isApiErrorMessage(text)) return text;
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode t = part.get("text");
                if (t != null) {
                    String s = t.asText();
                    if (!s.isBlank()) sb.append(s);
                }
            }
            if (!sb.isEmpty()) return sb.toString();
        }
        return null;
    }

    public String chatWithImage(String userMessage, byte[] imageBytes, String imageName) {
        String[] modelsToTry;
        if (visionModelName != null && !visionModelName.isBlank()) {
            modelsToTry = new String[]{visionModelName, "qwen-vl-plus", "qwen3-vl-plus", "qwen2.5-vl-72b-instruct", "qwen-vl-max"};
        } else {
            modelsToTry = new String[]{"qwen-vl-plus", "qwen3-vl-plus", "qwen2.5-vl-72b-instruct", "qwen-vl-max"};
        }

        String ext = "png";
        if (imageName != null) {
            int dot = imageName.lastIndexOf('.');
            if (dot >= 0) ext = imageName.substring(dot + 1);
        }
        String mime = switch (ext.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/png";
        };
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUri = "data:" + mime + ";base64," + base64;

        for (String model : modelsToTry) {
            log.info("尝试视觉模型: {}", model);
            String result = tryVisionModel(model, userMessage, dataUri);
            if (result != null) return result;
        }

        log.error("所有视觉模型均不可用");
        return null;
    }

    private String tryVisionModel(String modelName, String userMessage, String dataUri) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode systemMsg = mapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个智能助手，请用中文回答。回答简洁明了，一般不超过200字。不要使用表情符号。");
        messages.add(systemMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode contentArray = mapper.createArrayNode();

        ObjectNode imageNode = mapper.createObjectNode();
        imageNode.put("type", "image_url");
        ObjectNode imageUrlNode = mapper.createObjectNode();
        imageUrlNode.put("url", dataUri);
        imageNode.set("image_url", imageUrlNode);
        contentArray.add(imageNode);

        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("type", "text");
        String question = (userMessage != null && !userMessage.isBlank())
                ? userMessage : "请详细描述这张图片的内容。";
        textNode.put("text", question);
        contentArray.add(textNode);

        userMsg.set("content", contentArray);
        messages.add(userMsg);

        body.set("messages", messages);

        String requestBody = body.toString();
        log.debug("DashScope VL 请求: model={}, url={}", modelName, visionApiUrl);

        Request request = new Request.Builder()
                .url(visionApiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.warn("视觉模型 {} 不可用: status={}, body={}", modelName, response.code(), respBody);
                return null;
            }
            log.info("视觉模型 {} 请求成功", modelName);
            return parseVisionReply(respBody);
        } catch (IOException e) {
            log.warn("视觉模型 {} 调用异常: {}", modelName, e.getMessage());
            return null;
        }
    }

    private String parseVisionReply(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);
            if (hasTopLevelError(root)) {
                log.warn("视觉识别响应包含顶层错误: {}", respBody);
                return null;
            }

            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null && !content.asText().isBlank()) {
                        String text = content.asText();
                        if (isApiErrorMessage(text)) {
                            log.warn("视觉识别响应内容为错误消息: {}", text);
                            return null;
                        }
                        return text;
                    }
                }
            }

            JsonNode output = root.get("output");
            if (output != null) {
                JsonNode text = output.get("text");
                if (text != null && !text.asText().isBlank()) {
                    String t = text.asText();
                    if (isApiErrorMessage(t)) {
                        log.warn("视觉识别 output 为错误消息: {}", t);
                        return null;
                    }
                    return t;
                }
            }

            log.error("无法从视觉响应中提取内容: {}", respBody);
        } catch (Exception e) {
            log.error("解析视觉响应失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 视频内容理解（百炼 Qwen-VL 视觉模型）
     */
    public String chatWithVideo(byte[] videoBytes, String fileName, String userMessage) {
        if (videoBytes == null || videoBytes.length == 0) {
            log.warn("视频数据为空");
            return null;
        }

        String[] modelsToTry;
        if (visionModelName != null && !visionModelName.isBlank()) {
            modelsToTry = new String[]{visionModelName, "qwen-vl-plus", "qwen3-vl-plus", "qwen-vl-max"};
        } else {
            modelsToTry = new String[]{"qwen-vl-plus", "qwen3-vl-plus", "qwen-vl-max"};
        }

        String mime = detectVideoMime(fileName);
        String videoUrl = buildVideoDataUrl(videoBytes, mime);
        if (videoUrl == null) {
            log.warn("视频过大（{} bytes），无法通过 Base64 传输，尝试上传获取公网 URL", videoBytes.length);
            videoUrl = uploadMediaForPublicUrl(videoBytes, fileName != null ? fileName : "video.mp4");
            if (videoUrl == null) {
                log.warn("大视频上传失败，当前视频大小: {} bytes", videoBytes.length);
                return null;
            }
        }

        String question = (userMessage != null && !userMessage.isBlank())
                ? userMessage : "请详细描述这段视频的内容。";

        for (String model : modelsToTry) {
            log.info("尝试视频理解模型: {}", model);
            String result = tryVideoModel(model, videoUrl, question);
            if (result != null) return result;
        }

        log.error("所有视频理解模型均不可用");
        return null;
    }

    /**
     * 文件内容分析：图片/音频/视频走多模态，文档走 Qwen-Long
     */
    public String analyzeFile(byte[] fileBytes, String fileName, String userMessage) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件数据为空");
            return null;
        }

        String name = (fileName != null && !fileName.isBlank()) ? fileName : "file.bin";
        String question = (userMessage != null && !userMessage.isBlank())
                ? userMessage : "请总结这份文件的主要内容。";

        if (isImageFile(name)) {
            log.info("文件为图片格式，使用视觉模型识别: {}", name);
            return chatWithImage(question, fileBytes, name);
        }
        if (isAudioFile(name)) {
            log.info("文件为音频格式，使用语音识别: {}", name);
            String transcribed = transcribeAudio(fileBytes);
            if (transcribed == null || transcribed.isBlank()) return null;
            return chat(question + "\n\n音频转写内容：\n" + transcribed, null);
        }
        if (isVideoFile(name)) {
            log.info("文件为视频格式，使用视频理解: {}", name);
            return chatWithVideo(fileBytes, name, question);
        }

        log.info("文件为文档格式，使用 Qwen-Long 分析: {}, 大小: {} bytes", name, fileBytes.length);
        String fileId = uploadFileForExtract(fileBytes, name);
        if (fileId == null) {
            log.warn("文档上传百炼失败: {}", name);
            return null;
        }
        if (!waitForFileProcessed(fileId)) {
            log.warn("文档解析超时或失败: fileId={}", fileId);
            return null;
        }
        return chatWithDocument(fileId, question);
    }

    private String buildVideoDataUrl(byte[] videoBytes, String mime) {
        if (videoBytes.length > VIDEO_BASE64_MAX_BYTES) return null;
        String base64 = Base64.getEncoder().encodeToString(videoBytes);
        return "data:" + mime + ";base64," + base64;
    }

    private String tryVideoModel(String modelName, String videoUrl, String userMessage) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", modelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode systemMsg = mapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个智能助手，请用中文回答。回答简洁明了，一般不超过200字。不要使用表情符号。");
        messages.add(systemMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");

        ArrayNode contentArray = mapper.createArrayNode();

        ObjectNode videoNode = mapper.createObjectNode();
        videoNode.put("type", "video_url");
        ObjectNode videoUrlNode = mapper.createObjectNode();
        videoUrlNode.put("url", videoUrl);
        videoNode.set("video_url", videoUrlNode);
        videoNode.put("fps", videoFps);
        contentArray.add(videoNode);

        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", userMessage);
        contentArray.add(textNode);

        userMsg.set("content", contentArray);
        messages.add(userMsg);
        body.set("messages", messages);

        try {
            String respBody = doHttpPost(visionApiUrl, body.toString());
            if (respBody == null) return null;
            String result = parseVisionReply(respBody);
            if (result == null) log.warn("视频模型 {} 无法解析响应", modelName);
            return result;
        } catch (Exception e) {
            log.warn("视频模型 {} 调用异常: {}", modelName, e.getMessage());
            return null;
        }
    }

    private String uploadFileForExtract(byte[] fileBytes, String fileName) {
        try {
            RequestBody uploadBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                            RequestBody.create(fileBytes, MediaType.parse("application/octet-stream")))
                    .addFormDataPart("purpose", "file-extract")
                    .build();

            Request request = new Request.Builder()
                    .url(FILES_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .post(uploadBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("文档上传失败: status={}, body={}", response.code(), respBody);
                    return null;
                }
                JsonNode root = mapper.readTree(respBody);
                JsonNode id = root.get("id");
                if (id != null && !id.asText().isBlank()) {
                    log.info("文档上传成功, fileId={}", id.asText());
                    return id.asText();
                }
                log.warn("文档上传响应格式异常: {}", respBody);
            }
        } catch (Exception e) {
            log.warn("文档上传异常: {}", e.getMessage());
        }
        return null;
    }

    private boolean waitForFileProcessed(String fileId) {
        String url = FILES_API_URL + "/" + fileId;
        try {
            for (int i = 0; i < 30; i++) {
                Request request = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + apiKey)
                        .get()
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String respBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        JsonNode root = mapper.readTree(respBody);
                        String status = root.path("status").asText("");
                        log.info("文档解析状态: fileId={}, status={}", fileId, status);
                        if ("processed".equals(status)) return true;
                        if ("error".equals(status)) {
                            log.warn("文档解析失败: {}", respBody);
                            return false;
                        }
                    }
                }
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            log.warn("轮询文档解析状态异常: {}", e.getMessage());
        }
        return false;
    }

    private String chatWithDocument(String fileId, String userMessage) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", documentModelName);

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode sys1 = mapper.createObjectNode();
        sys1.put("role", "system");
        sys1.put("content", "你是一个智能助手，请用中文回答。回答简洁明了，一般不超过200字。不要使用表情符号。");
        messages.add(sys1);

        ObjectNode sys2 = mapper.createObjectNode();
        sys2.put("role", "system");
        sys2.put("content", "fileid://" + fileId);
        messages.add(sys2);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        body.set("messages", messages);

        try {
            String respBody = doHttpPost(visionApiUrl, body.toString());
            if (respBody == null) return null;
            return parseTextReply(respBody);
        } catch (Exception e) {
            log.warn("文档问答异常: {}", e.getMessage());
            return null;
        }
    }

    private String uploadMediaForPublicUrl(byte[] fileBytes, String fileName) {
        try {
            Path tempDir = Files.createTempDirectory("media_");
            Path tempFile = tempDir.resolve(fileName);
            Files.write(tempFile, fileBytes);
            String url = uploadToTempHosting(tempFile);
            Files.deleteIfExists(tempFile);
            Files.deleteIfExists(tempDir);
            return url;
        } catch (Exception e) {
            log.warn("媒体文件上传公网失败: {}", e.getMessage());
            return null;
        }
    }

    private static String detectVideoMime(String fileName) {
        if (fileName == null) return "video/mp4";
        String ext = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "mp4";
        return switch (ext) {
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "webm" -> "video/webm";
            case "mkv" -> "video/x-matroska";
            default -> "video/mp4";
        };
    }

    private static boolean isImageFile(String fileName) {
        String ext = getExtension(fileName);
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp");
    }

    private static boolean isAudioFile(String fileName) {
        String ext = getExtension(fileName);
        return ext.equals("mp3") || ext.equals("wav") || ext.equals("m4a")
                || ext.equals("aac") || ext.equals("ogg") || ext.equals("flac")
                || ext.equals("amr") || ext.equals("silk");
    }

    private static boolean isVideoFile(String fileName) {
        String ext = getExtension(fileName);
        return ext.equals("mp4") || ext.equals("mov") || ext.equals("avi")
                || ext.equals("webm") || ext.equals("mkv");
    }

    private static String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private String doRequest(ObjectNode body) {
        String requestBody = body.toString();
        log.debug("DashScope 请求: {}", requestBody);

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            log.debug("DashScope 响应: status={}, body={}", response.code(), respBody);

            if (!response.isSuccessful()) {
                log.error("DashScope API 请求失败: status={}, body={}", response.code(), respBody);
                return "抱歉，AI 服务暂时不可用，请稍后再试。";
            }
            return parseReply(respBody);
        } catch (IOException e) {
            log.error("DashScope API 调用异常: {}", e.getMessage());
            return "抱歉，网络异常，请稍后再试。";
        }
    }

    private String parseReply(String respBody) {
        try {
            JsonNode root = mapper.readTree(respBody);

            JsonNode output = root.get("output");
            if (output == null) {
                log.error("响应中没有 output 字段: {}", respBody);
                return "抱歉，我没能理解您的意思，请换个问法试试。";
            }

            JsonNode text = output.get("text");
            if (text != null && !text.asText().isBlank()) return text.asText();

            JsonNode choices = output.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode firstChoice = choices.get(0);
                JsonNode message = firstChoice.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null && !content.asText().isBlank()) return content.asText();
                }
            }

            log.error("无法从响应中提取回复内容: {}", respBody);
        } catch (Exception e) {
            log.error("解析 DashScope 响应失败: {}", e.getMessage());
        }
        return "抱歉，我没能理解您的意思，请换个问法试试。";
    }
}

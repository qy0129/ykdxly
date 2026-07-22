package com.example.ilink.routing;

import com.example.ilink.config.Config;
import com.example.ilink.feature.persona.Personas;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 唯一的用户意图识别入口。
 *
 * <p>把用户自然语言和会话上下文发送给路由模型，并将模型返回的 JSON
 * 转换为 {@link IntentResult}。本类只负责识别，不执行具体业务。</p>
 */
public final class IntentRecognizer {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /** 创建意图识别器并注入 HTTP 客户端。 */
    public IntentRecognizer(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** 调用路由模型，把自然语言转换为结构化意图结果。 */
    public IntentResult recognize(String userId, String userMessage, IntentContext context) {
        // 路由模型只输出结构化意图，业务执行由 UserRequestHandler 负责。
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.ROUTER_MODEL);
            body.addProperty("temperature", 0.1);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", buildSystemPrompt(userId, context));
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMessage);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[意图识别] 请求失败，HTTP "
                        + response.statusCode() + "：" + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            JsonObject result = parseJsonObject(content);

            String intent = result.get("intent").getAsString();
            return new IntentResult(
                    intent,
                    result.get("en_prompt").getAsString(),
                    result.get("cn_description").getAsString(),
                    result.get("image_size").getAsString(),
                    result.get("reply_mode").getAsString(),
                    result.get("voice_style").getAsString(),
                    result.get("persona").getAsString(),
                    result.get("image_action").getAsString(),
                    result.get("image_prompt").getAsString(),
                    result.get("audio_source").getAsString(),
                    result.get("audio_index").getAsInt(),
                    result.get("document_action").getAsString(),
                    result.get("output_file_type").getAsString(),
                    result.get("weather_location").getAsString(),
                    result.get("weather_day").getAsString());
        } catch (Exception e) {
            System.err.println("[意图识别] 识别失败：" + e.getMessage());
            return null;
        }
    }

    /** 构造路由模型的系统提示词和当前会话状态说明。 */
    private String buildSystemPrompt(String userId, IntentContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你只负责用户意图路由，不回答用户问题。必须严格依据语义判断，禁止仅凭单个词触发功能。只输出一行JSON。\n");
        prompt.append("当前状态：pending_image=").append(context.pendingImage())
                .append(", has_last_image=").append(context.hasLastImage())
                .append(", pending_draw_size=").append(context.pendingDraw())
                .append(", has_document=").append(context.hasDocument()).append("。\n");
        prompt.append("可选人设名称：").append(String.join("、", Personas.getAll().keySet())).append("。\n\n");

        prompt.append("意图规则：\n");
        prompt.append("1. chat：问答、聊天、讲笑话、写作、翻译、总结、建议以及所有不属于下述功能的请求。"
                + "语音输出和音色要求只是回复形式，不会把 chat 变成 draw。"
                + "例如‘用小男孩的音色给我讲个笑话’必须是 chat、reply_mode=voice、voice_style=boy。\n");
        prompt.append("2. draw：用户明确要求生成、绘制一张新图片时使用。"
                + "必须存在创建视觉内容的明确语义；仅出现‘画面感’、‘讲故事’、‘声音’等词不能判为 draw。"
                + "将英文绘图提示写入 en_prompt，中文画面说明写入 cn_description。\n");
        prompt.append("3. persona_switch：用户明确要求切换机器人长期说话人设时使用，persona 必须从可选人设名称中原样选择。"
                + "音色、男声、女声、温柔声音不属于人设切换。\n");
        prompt.append("4. audio_transcribe：用户明确要求获取某条历史语音的文字、转写或内容时使用。"
                + "audio_source 表示机器人、用户或任意来源；audio_index 从最新一条开始计数。\n");
        prompt.append("5. image_action：用户明确要求分析、解题或修改已发送图片时使用。"
                + "只有 pending_image=true，或用户明确指向上一张图片且 has_last_image=true 时才可使用。"
                + "image_action 选择 analyze、solve、edit 或 clarify，完整要求写入 image_prompt。\n");
        prompt.append("6. draw_size：仅当 pending_draw_size=true 且用户正在回答图片尺寸时使用。"
                + "方形对应1024x1024，竖屏对应768x1024，横屏对应1024x576。"
                + "如果用户转而提出无关请求，应按新请求判断，不要强制 draw_size。\n\n");
        prompt.append("7. document_summary：当前有文件且用户要求总结文件时使用。\n");
        prompt.append("8. document_question：当前有文件且用户根据文件内容提问时使用。\n");
        prompt.append("9. generate_file：用户明确要求把文件总结或回答整理成 PDF 或 DOCX 时使用。\n");
        prompt.append("document_action 只能是 none|summary|question；没有文件时必须为 none。"
                + "生成文件时 output_file_type 为 docx 或 pdf，否则为 none。\n\n");
        prompt.append("10. weather：用户明确查询某个城市、区县、乡镇的天气、温度、降雨或风力时使用。"
                + "weather_location 必须填写可供 Open-Meteo 检索的英文地点名，例如北京填 Beijing，上海填 Shanghai，"
                + "和平镇填 Heping；用户提供了省、市、县时也要保留这些英文行政区信息。"
                + "今天或当前天气时 weather_day=today，明天天气时 weather_day=tomorrow。"
                + "用户未说明地点时 weather_location 为空。\n\n");

        prompt.append("Document rules: when has_document=true, use document_summary for summarizing, document_question for questions, document_edit when the user asks to modify, rewrite, delete, add, or correct the current document, and generate_file when the user asks for a PDF or DOCX output. document_action must be none, summary, question, or edit. output_file_type must be none, docx, or pdf.\n");
        prompt.append("输出规则：\n");
        prompt.append("- 用户明确只要语音时 reply_mode=voice；明确同时要文字和语音时为both；要求关闭语音时为text；否则为keep。\n");
        prompt.append("- voice_style：小男孩=boy，小女孩=girl，成年男声=male，成年女声=female，温柔柔和=warm，活泼元气=lively，无要求=default。\n");
        prompt.append("- 未使用的字符串字段填空字符串，image_size填none，image_action填none，audio_source填any，audio_index填1，weather_day填today。\n");
        prompt.append("最高优先级校验示例：用户输入‘用小男孩的音色给我讲个笑话’时，"
                + "intent必须为chat，reply_mode必须为voice，voice_style必须为boy，"
                + "en_prompt和cn_description必须为空，image_size必须为none。\n");
        prompt.append("提交结果前逐项检查：音色要求是否正确写入voice_style；语音要求是否正确写入reply_mode；"
                + "没有明确生成图片要求时intent绝不能为draw。");
        prompt.append("\n输出必须包含以下全部字段："
                + "{\"intent\":\"chat|draw|persona_switch|audio_transcribe|image_action|draw_size|document_summary|document_question|generate_file|document_edit|weather\","
                + "\"en_prompt\":\"\",\"cn_description\":\"\","
                + "\"image_size\":\"none|1024x1024|768x1024|1024x576\","
                + "\"reply_mode\":\"keep|text|voice|both\","
                + "\"voice_style\":\"default|boy|girl|male|female|warm|lively\","
                + "\"persona\":\"\",\"image_action\":\"none|analyze|solve|edit|clarify\","
                + "\"image_prompt\":\"\",\"audio_source\":\"any|bot|user\",\"audio_index\":1,"
                + "\"document_action\":\"none|summary|question\",\"output_file_type\":\"none|docx|pdf\","
                + "\"weather_location\":\"\",\"weather_day\":\"today|tomorrow\"}。");
        return prompt.toString();
    }

    /** 从模型文本中提取 JSON 对象，处理代码块和多余说明文字。 */
    private JsonObject parseJsonObject(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                json = json.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }


}

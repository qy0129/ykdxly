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
                    result.get("weather_day").getAsString(),
                    string(result, "plan_goal"),
                    string(result, "plan_deadline"),
                    string(result, "plan_available_time"),
                    string(result, "calculation_operation"),
                    string(result, "calculation_left"),
                    string(result, "calculation_right"),
                    string(result, "calculation_quantity"),
                    string(result, "calculation_unit_price"),
                    string(result, "calculation_discount_percent"),
                    string(result, "travel_origin"),
                    string(result, "travel_destination"),
                    string(result, "travel_departure_time"),
                    integer(result, "time_budget_minutes", 0),
                    string(result, "meal_keyword"),
                    string(result, "diet_goal"),
                    string(result, "nearby_location"),
                    string(result, "nearby_action"),
                    string(result, "calendar_action"),
                    string(result, "calendar_title"),
                    string(result, "calendar_time"),
                    string(result, "calendar_recurrence"),
                    integer(result, "calendar_reminder_minutes", 0));
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
        prompt.append("5. image_action：用户明确要求分析、解题或修改已发送图片本身时使用。"
                + "只有 pending_image=true，或用户明确指向上一张图片且 has_last_image=true 时才可使用。"
                + "⚠️ 以下情况不是 image_action，必须判为 document_edit："
                + "A）插入图片到文档：'把图片放到文档里'、'把图片插入文档'、'在文档中添加图片'；"
                + "B）图片内容生成新文档：'图片转文档'、'拍照转文档'、'把图片上的文字整理成文档/表格'。"
                + "A 是把图片文件嵌入已有文档，必须是 document_edit；B 是根据本轮图片内容新建文件，必须是 generate_file，不能使用历史文档。"
                + "image_action 仅用于对图片本身做操作（分析图片内容、解题、美化、裁剪、加滤镜）。"
                + "image_action 选择 analyze、solve、edit 或 clarify，完整要求写入 image_prompt。\n");
        prompt.append("6. draw_size：仅当 pending_draw_size=true 且用户正在回答图片尺寸时使用。"
                + "方形对应1024x1024，竖屏对应768x1024，横屏对应1024x576。"
                + "如果用户转而提出无关请求，应按新请求判断，不要强制 draw_size。\n\n");
        prompt.append("7. document_summary：当前有文件且用户要求总结文件时使用。\n");
        prompt.append("8. document_question：当前有文件且用户根据文件内容提问时使用。\n");
        prompt.append("9. generate_file：用户要求从零创建新文件、或把聊天回答整理成文件时使用。"
                + "⚠️ 禁止用于格式转换！'转成Word/PDF'、'改成docx/pdf格式'、'导出为Word' 这类请求必须判为 document_edit，绝不能判为 generate_file。\n");
        prompt.append("document_action 只能是 none|summary|question|edit；没有文件时必须为 none。"
                + "编辑或格式转换时 output_file_type 为用户指定的格式（docx/pdf/xlsx/pptx/txt/md/csv），否则为 none。\n\n");
        prompt.append("10. weather：用户明确查询某个城市、区县、乡镇的天气、温度、降雨或风力时使用。"
                + "weather_location 必须填写可供 Open-Meteo 检索的英文地点名，例如北京填 Beijing，上海填 Shanghai，"
                + "和平镇填 Heping；用户提供了省、市、县时也要保留这些英文行政区信息。"
                + "今天或当前天气时 weather_day=today，明天天气时 weather_day=tomorrow。"
                + "用户未说明地点时 weather_location 为空。\n\n");
        prompt.append("11. task_plan：用户要求制定学习、项目、工作或生活任务计划时使用。"
                + "plan_goal 填写最终目标，plan_deadline 只填写真正的截止日期或时刻，例如后天或3天后，"
                + "plan_available_time 填写每天或各时间段可用时间。若用户说‘我有一小时写作业’，"
                + "这不是截止时间，time_budget_minutes=60，plan_deadline 为空。"
                + "用户同时要求生成Word或PDF时仍然使用task_plan，并设置output_file_type。\n");
        prompt.append("12. plan_adjust：用户要求调整、延期、重新安排当前计划，或说明某项任务已经完成时使用。\n");
        prompt.append("13. plan_progress：用户询问当前计划完成情况、下一项任务或还剩什么时使用。\n\n");
        prompt.append("14. calculator：用户要求四则运算、百分比、折扣、总价、单位换算、汇率、进制、BMI、个税、房贷、"
                + "中文大写金额或亲戚称呼计算时使用。自然语言参数由 CalculatorService 再调用专用工具处理；"
                + "基础四则运算仍可直接使用 calculation_operation、calculation_left、calculation_right 等字段。\n\n");
        prompt.append("15. expense_split：用户要求多人 AA、平分消费、根据不同已付款金额结算或计算谁该转给谁时使用。"
                + "例如“我们三个人吃饭300元，我付了200，张三付了100，怎么算”。\n");
        prompt.append("16. deadline_countdown：用户询问距离某个截止日期或时间还有多久、剩余几天几小时、是否超时时使用。"
                + "将明确的时间表达写入 plan_deadline，例如明天下午六点、2026-07-25 18:00。\n\n");
        prompt.append("17. travel_plan：用户给出起点、终点并要求路线、导航、出行安排或中途停留时使用。"
                + "travel_origin、travel_destination 必须分别填写地点；‘一个小时’等可用时长填入time_budget_minutes。"
                + "中途想吃面、咖啡等填入meal_keyword；明确出发时间填travel_departure_time。"
                + "即使用户说‘帮我规划’，只要核心是从A到B出行，必须是travel_plan，绝不能是task_plan。\n");
        prompt.append("18. diet_plan：用户要求饮食规划、外卖推荐、减脂餐、增肌餐或控糖餐时使用。"
                + "diet_goal 填减脂、增肌、控糖、维持体重或空字符串；不要把附近餐厅搜索判为diet_plan。\n");
        prompt.append("19. nearby_food：用户说自己在某位置、询问附近有什么好吃的、附近餐厅或附近外卖时使用。"
                + "nearby_location 填用户明确说出的地点，未重复时可留空；nearby_action 只能是remember或search。\n");
        prompt.append("20. calendar_event：用户创建、查询、完成、取消或延后提醒/日程时使用。"
                + "calendar_action 为create|list|complete|cancel|snooze；创建时填写calendar_title、calendar_time、"
                + "calendar_recurrence(none|daily|weekly|monthly|yearly)和calendar_reminder_minutes。\n");
        prompt.append("21. planning_capabilities：用户询问‘你可以帮我做什么规划’、规划功能有哪些时使用。\n\n");

        prompt.append("Document rules: when has_document=true, use document_summary for summarizing, document_question for questions, document_edit when the user asks to modify, rewrite, delete, add, correct, insert image into document, or convert the format of the current document (PDF转Word/Word转PDF等), and generate_file only when the user asks to create a brand new file from scratch. document_action must be none, summary, question, or edit. output_file_type supports: docx, pdf, xlsx, pptx, txt, md, csv, or none. "
        + "⚠️ 强制规则：'转成Word/PDF/Excel'='改成xxx格式'='导出为xxx' → 必须 document_edit；'把图片放到/插入/添加到文档' → 必须 document_edit。"
        + "output_file_type 规则：用户说'生成 Word/文档/转成Word/改成Word格式' → docx；说'PDF/转成PDF' → pdf；说'Excel/xlsx' → xlsx；说'PPT/pptx' → pptx；说'TXT/文本' → txt；说'Markdown/md' → md；说'CSV' → csv；未明确说明 → none。\n");
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
                + "{\"intent\":\"chat|draw|persona_switch|audio_transcribe|image_action|draw_size|document_summary|document_question|generate_file|document_edit|weather|task_plan|plan_adjust|plan_progress|calculator|expense_split|deadline_countdown|travel_plan|diet_plan|nearby_food|calendar_event|planning_capabilities\","
                + "\"en_prompt\":\"\",\"cn_description\":\"\","
                + "\"image_size\":\"none|1024x1024|768x1024|1024x576\","
                + "\"reply_mode\":\"keep|text|voice|both\","
                + "\"voice_style\":\"default|boy|girl|male|female|warm|lively\","
                + "\"persona\":\"\",\"image_action\":\"none|analyze|solve|edit|clarify\","
                + "\"image_prompt\":\"\",\"audio_source\":\"any|bot|user\",\"audio_index\":1,"
                + "\"document_action\":\"none|summary|question|edit\",\"output_file_type\":\"none|docx|pdf|xlsx|pptx|txt|md|csv\","
                + "\"weather_location\":\"\",\"weather_day\":\"today|tomorrow\","
                + "\"plan_goal\":\"\",\"plan_deadline\":\"\",\"plan_available_time\":\"\","
                + "\"calculation_operation\":\"add|subtract|multiply|divide|percentage|total_price\","
                + "\"calculation_left\":\"0\",\"calculation_right\":\"0\","
                + "\"calculation_quantity\":\"1\",\"calculation_unit_price\":\"0\","
                + "\"calculation_discount_percent\":\"0\","
                + "\"travel_origin\":\"\",\"travel_destination\":\"\",\"travel_departure_time\":\"\","
                + "\"time_budget_minutes\":0,\"meal_keyword\":\"\",\"diet_goal\":\"\","
                + "\"nearby_location\":\"\",\"nearby_action\":\"remember|search\","
                + "\"calendar_action\":\"create|list|complete|cancel|snooze\",\"calendar_title\":\"\","
                + "\"calendar_time\":\"\",\"calendar_recurrence\":\"none|daily|weekly|monthly|yearly\","
                + "\"calendar_reminder_minutes\":0}。");
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

    /** 读取可选字符串字段，模型省略时返回空字符串。 */
    private String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString() : "";
    }

    /** 读取可选整数，模型遗漏或格式异常时使用默认值，避免路由失败影响普通聊天。 */
    private int integer(JsonObject object, String name, int defaultValue) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsInt() : defaultValue;
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }


}

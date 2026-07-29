package com.example.ilink.application.routing;

import com.example.ilink.capabilities.persona.Personas;
import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

/** 为需求拆分和能力分配两个阶段构造提示词。 */
public final class RoutePromptBuilder {

    private final CapabilityRegistry capabilities;
    private final Gson gson = new Gson();

    public RoutePromptBuilder(CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    public String buildRequirementPrompt(RoutingContext context) {
        return """
                你是需求拆分器，只负责理解用户完整原话，不选择工具、不回答问题。
                把用户一句话拆成所有可独立完成的原子需求。不同动作、不同对象、不同时间目标必须分别保留；
                同一个动作的起点、终点、时间、品牌等参数不要拆开。不要因为前一个需求明显就忽略后文。
                地名是需求边界的重要语义线索，但不能把相邻句子的地名拼接成一个地点。
                规划类总目标不能吞掉其中明确要求的天气、打车、外卖、计算、提醒等具体动作。
                保持原文顺序；后一步需要前一步结果时填写depends_on。最多12项。
                只输出JSON：{"requirements":[{"id":"r1","text":"贴近原文的完整原子需求","depends_on":[]}]}

                正确示例：
                输入：今天杭州天气怎么样？我现在想打车去西湖
                输出：{"requirements":[{"id":"r1","text":"查询今天杭州的天气","depends_on":[]},{"id":"r2","text":"现在打车去西湖","depends_on":[]}]}
                输入：规划明天行程，先打车去梦想小镇，路上查余杭天气，中午点麦当劳，下午算往返车费，4点提醒离开
                输出必须包含打车、天气、外卖、费用计算、提醒五项，行程汇总可另列一项。
                """ + contextBlock(context);
    }

    public String buildAssignmentPrompt(RoutingContext context, String originalRequest,
                                        List<AtomicRequirement> requirements) {
        StringBuilder prompt = new StringBuilder("""
                你是能力分配器和覆盖审计器。必须为每个requirement恰好分配一个最匹配的能力，
                同时对照original_request检查第一阶段是否漏掉了明确需求。
                所有能力同级，没有路由优先级；按语义和能力描述选择，禁止合并、遗漏或新增需求。
                action_text只写当前原子需求。requirement_id和depends_on必须原样返回。
                交互型能力可以进入等待状态，执行器会在用户一次选择后继续剩余动作，不要因此省略它。
                地点字段只能来自当前原子需求、明确上下文或已确认位置，禁止把另一个需求的整句拼进地点。
                weather_location保留中文地名；下游天气服务负责地理编码。

                可用能力（同级）：
                如果原句中存在未被任何requirement覆盖的明确动作，把它逐项写入missing_requirements；没有则返回空数组。
                """);
        for (CapabilityDefinition capability : capabilities.all()) {
            prompt.append("- ").append(capability.name()).append(": ")
                    .append(capability.description());
            if (!capability.parameterHint().isBlank()) {
                prompt.append("；参数=").append(capability.parameterHint());
            }
            prompt.append("；交互=").append(capability.interactive()).append('\n');
        }
        prompt.append("\n可选人设：").append(String.join("、", Personas.getAll().keySet())).append("。\n");
        prompt.append(parameterRules());
        prompt.append("\n原始请求：").append(originalRequest).append('\n');
        prompt.append("原子需求JSON：").append(gson.toJson(requirements)).append('\n');
        prompt.append(contextBlock(context));
        return prompt.toString();
    }

    public String buildMissingAssignmentPrompt(RoutingContext context, String originalRequest,
                                               List<AtomicRequirement> missingRequirements) {
        return buildAssignmentPrompt(context, originalRequest, missingRequirements)
                + "\n这是覆盖校验后的补偿调用，只输出以上遗漏需求对应的actions，missing_requirements返回空数组。";
    }

    private String contextBlock(RoutingContext context) {
        IntentContext media = context.mediaContext();
        StringBuilder value = new StringBuilder("\n完整RoutingContext：\n")
                .append("- 当前时间：").append(context.currentTime()).append('\n')
                .append("- 时区：").append(context.currentTime().getZone()).append('\n')
                .append("- 人设：").append(context.persona()).append('\n')
                .append("- 长期记忆：").append(context.memories()).append('\n')
                .append("- 会话摘要：").append(context.conversationSummary()).append('\n')
                .append("- 已确认位置：").append(context.currentLocation()).append('\n')
                .append("- 已确认城市：").append(context.currentCity()).append('\n')
                .append("- 媒体状态：pending_image=").append(media.pendingImage())
                .append(", has_last_image=").append(media.hasLastImage())
                .append(", pending_draw=").append(media.pendingDraw())
                .append(", has_document=").append(media.hasDocument())
                .append(", pending_calendar=").append(media.pendingCalendar()).append('\n')
                .append("- 全部等待状态：").append(enabledPendingStates(context.pendingStates())).append('\n')
                .append("- 最近消息：\n");
        for (MySqlStore.ChatEntry message : context.recentMessages()) {
            value.append("  ").append(message.role()).append(": ")
                    .append(limit(message.content(), 500)).append('\n');
        }
        return value.toString();
    }

    private String enabledPendingStates(Map<String, Boolean> states) {
        List<String> enabled = states.entrySet().stream().filter(Map.Entry::getValue)
                .map(Map.Entry::getKey).toList();
        return enabled.isEmpty() ? "无" : String.join(",", enabled);
    }

    private String parameterRules() {
        return """

                参数规则：
                - 打车用taxi_trip；一般路线/导航用travel_plan；指定品牌点外卖用food_order；附近餐厅搜索用nearby_food。
                - 创建提醒用calendar_event/create。费用预估、总价、算术和换算用calculator，并把完整表达保留在action_text。
                - weather_day使用today|tomorrow|today_morning|today_afternoon|today_evening|tomorrow_morning|tomorrow_afternoon|tomorrow_evening。
                - 回复方式reply_mode使用keep|text|voice|both；voice_style使用default|boy|girl|male|female|warm|lively。
                - 用户没有明确生成图片时不能选择draw；格式转换属于document_edit，不属于generate_file。
                - 未使用的字符串为空，数组为空数组，image_size/image_action/document_action/output_file_type使用none。
                - 只输出JSON对象，不输出解释或Markdown。结构必须是：
                {"missing_requirements":[{"id":"r_new","text":"遗漏的原子需求","depends_on":[]}],\
                "actions":[{"requirement_id":"r1","depends_on":[],"action_text":"",\
                "intent":"能力名","en_prompt":"","cn_description":"","image_size":"none",\
                "reply_mode":"keep","voice_style":"default","persona":"","image_action":"none",\
                "image_prompt":"","audio_source":"any","audio_index":1,"document_action":"none",\
                "output_file_type":"none","weather_location":"","weather_day":"today",\
                "plan_goal":"","plan_deadline":"","plan_available_time":"",\
                "calculation_operation":"","calculation_left":"","calculation_right":"",\
                "calculation_quantity":"","calculation_unit_price":"","calculation_discount_percent":"",\
                "travel_origin":"","travel_destination":"","travel_stops":[],"origin_city":"",\
                "destination_city":"","travel_departure_time":"","time_budget_minutes":0,"meal_keyword":"",\
                "diet_goal":"","nearby_location":"","nearby_action":"search","calendar_action":"create",\
                "calendar_title":"","calendar_time":"","calendar_recurrence":"none",\
                "calendar_reminder_minutes":0,"calendar_time_type":"auto","calendar_time_amount":0,\
                "calendar_time_unit":"","calendar_lead_time_seconds":0,"bilibili_query":"",\
                "bilibili_category":"video","media_query":"","media_category":"music",\
                "email_action":"unread","email_keyword":"","food_order_restaurants":""}]}
                """;
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}

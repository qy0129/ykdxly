package com.example.ilink.application.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 路由能力的唯一注册表；提示词、校验和执行计划都以这里为准。 */
public final class CapabilityRegistry {

    private final Map<String, CapabilityDefinition> capabilities;

    public CapabilityRegistry(List<CapabilityDefinition> definitions) {
        Map<String, CapabilityDefinition> indexed = new LinkedHashMap<>();
        for (CapabilityDefinition definition : definitions) indexed.put(definition.name(), definition);
        capabilities = Map.copyOf(indexed);
    }

    public List<CapabilityDefinition> all() {
        return capabilities.values().stream().toList();
    }

    public Set<String> names() {
        return capabilities.keySet();
    }

    public boolean contains(String name) {
        return capabilities.containsKey(name);
    }

    public static CapabilityRegistry defaults() {
        return new CapabilityRegistry(List.of(
                c("chat", "普通问答、聊天、写作、翻译、建议或最终汇总", "reply_mode, voice_style", false),
                c("draw", "生成一张新图片", "en_prompt, cn_description, image_size", true),
                c("persona_switch", "切换长期说话人设", "persona", false),
                c("audio_transcribe", "转写历史语音", "audio_source, audio_index", false),
                c("image_action", "分析、解题或修改已发送图片", "image_action, image_prompt", true),
                c("draw_size", "补充上一轮绘图尺寸", "image_size", true),
                c("document_summary", "总结当前文件", "document_action=summary", false),
                c("document_question", "基于当前文件回答问题", "document_action=question", false),
                c("generate_file", "从零创建新文件", "output_file_type", true),
                c("document_edit", "编辑当前文件或转换格式", "document_action=edit, output_file_type", true),
                c("weather", "查询指定地点和时段的天气", "weather_location, weather_day", false),
                c("task_plan", "制定学习、工作或生活任务计划", "plan_goal, plan_deadline, plan_available_time", true),
                c("plan_adjust", "调整当前计划", "action_text", true),
                c("plan_progress", "查询当前计划进度", "action_text", false),
                c("calculator", "计算、换算、汇率、总价或费用估算", "calculation_*；复杂表达保留action_text", false),
                c("expense_split", "多人AA和转账结算", "action_text", false),
                c("deadline_countdown", "计算距离截止时间还有多久", "plan_deadline", false),
                c("travel_plan", "路线、导航和多站点出行安排", "travel_origin, travel_destination, travel_stops, travel_departure_time", true),
                c("taxi_trip", "打车、叫车、打车报价及订单操作", "travel_origin, travel_destination, origin_city, destination_city", true),
                c("diet_plan", "营养、减脂、增肌等饮食规划", "diet_goal", true),
                c("nearby_food", "搜索某地点附近餐厅或食物", "nearby_location, meal_keyword, nearby_action", true),
                c("calendar_event", "创建、查询、完成、取消或延后提醒", "calendar_action, calendar_title, calendar_time, recurrence", true),
                c("planning_capabilities", "介绍可用的规划能力", "", false),
                c("bilibili_search", "搜索视频、课程、音乐或剧集", "bilibili_query, bilibili_category", false),
                c("media_lookup", "查询动漫、剧集、歌手、专辑或歌词资料", "media_query, media_category", false),
                c("email_query", "查询QQ邮箱", "email_action, email_keyword", false),
                c("food_order", "指定餐厅或品牌点外卖", "food_order_restaurants, nearby_location", true),
                c("todo", "创建、查看、完成或删除待办", "action_text", false),
                c("express_query", "查询快递、物流或运单", "action_text", true),
                c("news_search", "查询最新新闻、资讯或热搜", "action_text", false),
                c("web_search", "联网搜索网页或实时信息", "action_text", false),
                c("memory", "记住、忘记或查询长期记忆", "action_text", false),
                c("visual_card", "生成业务卡片、计划表、证书或互动卡片", "action_text", true)
        ));
    }

    private static CapabilityDefinition c(String name, String description,
                                          String parameters, boolean interactive) {
        return new CapabilityDefinition(name, description, parameters, interactive);
    }
}

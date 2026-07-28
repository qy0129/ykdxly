package com.example.ilink.application.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 保存路由层允许使用的能力名称。 */
public final class SkillRegistry {

    private final Map<String, BotSkill> skills;

    public SkillRegistry(List<BotSkill> skills) {
        Map<String, BotSkill> indexed = new LinkedHashMap<>();
        for (BotSkill skill : skills) indexed.put(skill.name(), skill);
        this.skills = Map.copyOf(indexed);
    }

    public Set<String> names() {
        return skills.keySet();
    }

    public static SkillRegistry defaults() {
        return new SkillRegistry(List.of(
                skill("chat"), skill("draw"), skill("persona_switch"), skill("audio_transcribe"),
                skill("image_action"), skill("draw_size"), skill("document_summary"),
                skill("document_question"), skill("generate_file"), skill("document_edit"),
                skill("weather"), skill("task_plan"), skill("plan_adjust"), skill("plan_progress"),
                skill("calculator"), skill("expense_split"), skill("deadline_countdown"),
                skill("travel_plan"), skill("taxi_trip"), skill("diet_plan"), skill("nearby_food"),
                skill("calendar_event"), skill("planning_capabilities"), skill("bilibili_search"),
                skill("media_lookup"), skill("email_query"), skill("food_order")));
    }

    private static BotSkill skill(String name) {
        return new BotSkill(name, name);
    }
}

package com.example.ilink.application.skill;

import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;

/** 由 skill.json 创建的通用 Skill 实现。 */
public final class ConfiguredSkill implements Skill {
    private final SkillDefinition definition;
    private final ToolManager toolManager;

    public ConfiguredSkill(SkillDefinition definition, ToolManager toolManager) {
        this.definition = definition;
        this.toolManager = toolManager;
    }

    @Override
    public SkillDefinition definition() {
        return definition;
    }

    @Override
    public SkillResult execute(SkillRequest request, SkillContext context) {
        boolean ownsCapability = definition.capabilities().stream()
                .anyMatch(capability -> capability.name().equals(request.capability()));
        if (!ownsCapability) return SkillResult.failure("Skill 不支持能力：" + request.capability());
        if (definition.requiresApproval() && !context.approved()) {
            return SkillResult.failure("该 Skill 需要用户批准后执行");
        }
        if (!definition.toolNames().contains(request.toolName())) {
            return SkillResult.failure("Skill 未授权工具：" + request.toolName());
        }
        if (toolManager == null) return SkillResult.failure("工具管理器尚未初始化");
        return SkillResult.from(toolManager.execute(request.toolName(),
                new ToolContext(context.userId(), context.sessionId()), request.arguments()));
    }
}

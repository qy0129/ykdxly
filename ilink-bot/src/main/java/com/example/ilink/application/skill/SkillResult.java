package com.example.ilink.application.skill;

import com.example.ilink.application.tooling.ToolResult;

/** Skill 的统一执行结果。 */
public record SkillResult(boolean success, String output, Object data) {
    public static SkillResult failure(String output) {
        return new SkillResult(false, output, null);
    }

    public static SkillResult from(ToolResult result) {
        return new SkillResult(result.success(), result.output(), result.data());
    }
}

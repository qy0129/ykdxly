package com.example.ilink.application.skill;

import com.google.gson.JsonObject;

/** 一次 Skill 执行请求；toolName 必须出现在 Skill 清单中。 */
public record SkillRequest(String capability, String toolName, JsonObject arguments) {
    public SkillRequest {
        capability = capability == null ? "" : capability.trim();
        toolName = toolName == null ? "" : toolName.trim();
        arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
    }
}

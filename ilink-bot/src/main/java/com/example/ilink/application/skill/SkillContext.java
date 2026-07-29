package com.example.ilink.application.skill;

/** 由应用提供的执行身份与审批状态。 */
public record SkillContext(String userId, String sessionId, boolean approved) { }

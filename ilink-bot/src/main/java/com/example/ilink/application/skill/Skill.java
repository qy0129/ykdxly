package com.example.ilink.application.skill;

/** 可动态发现、描述和执行的一组业务能力。 */
public interface Skill {
    SkillDefinition definition();

    SkillResult execute(SkillRequest request, SkillContext context);
}

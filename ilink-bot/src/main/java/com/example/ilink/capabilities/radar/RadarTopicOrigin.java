package com.example.ilink.capabilities.radar;

/** 雷达主题的来源，明确关注不会被计划同步自动删除。 */
public enum RadarTopicOrigin {
    EXPLICIT_USER,
    PLAN_GOAL,
    PLAN_TASK,
    EXECUTIVE_TASK,
    MODEL_INFERENCE
}

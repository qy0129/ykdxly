package com.changlu.planner.agent.subagents.image.tools;

/** 文生图模型接入点抽象，便于测试替换与未来切换供应商。 */
public interface ImageGenerationProvider {
  /** 供应商标识，用于审计与重复调用判断。 */
  String name();

  /** 调用文生图模型，返回图片 URL；失败抛 {ImageGenerationException}，由上层按语言映射并决定是否重试。 */
  String generate(String prompt, String size, String style, int quality) throws Exception;
}
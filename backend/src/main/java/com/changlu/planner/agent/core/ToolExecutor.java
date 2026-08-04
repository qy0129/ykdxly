package com.changlu.planner.agent.core;

/** 统一执行入口；工具失败最多重试两次。 */
public final class ToolExecutor {
  @FunctionalInterface public interface Work<T> { T run() throws Exception; }

  public <T> T execute(Work<T> work) throws Exception {
    Exception last = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      try { return work.run(); }
      catch (Exception error) {
        last = error;
        if (attempt == 2) throw error;
      }
    }
    throw last;
  }
}

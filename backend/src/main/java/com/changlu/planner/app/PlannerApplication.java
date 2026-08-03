package com.changlu.planner.app;

import com.changlu.planner.integrations.wechat.WechatBotAgent;
import com.changlu.planner.interfaces.http.ApiServer;
import com.changlu.planner.shared.database.Database;

/** 应用组合根：这里只负责组装基础设施和启动顺序，不承载具体业务规则。 */
public final class PlannerApplication {
  private PlannerApplication() {}

  public static void main(String[] args) throws Exception {
    // 数据库必须先可用，HTTP 和微信入口都依赖同一个连接池与用户上下文。
    Database database = Database.fromEnvironment();
    database.start();
    database.ensureDefaultContext();
    ApiServer server = new ApiServer(database, Integer.parseInt(System.getenv().getOrDefault("PLANNER_PORT", "8081")));
    server.start();
    WechatBotAgent wechatBot = new WechatBotAgent(database);
    if (Boolean.parseBoolean(System.getenv().getOrDefault("PLANNER_WECHAT_ENABLED", "true"))) {
      wechatBot.start();
    } else {
      System.out.println("Wechat Bot disabled by PLANNER_WECHAT_ENABLED=false");
    }
    // 所有后台入口都在这里统一关闭，避免开发环境重启时遗留连接和线程。
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      wechatBot.close();
      server.stop();
      database.stop();
    }));
    System.out.println("Changlu Planner web listening on http://127.0.0.1:" + server.port() + "/");
  }
}

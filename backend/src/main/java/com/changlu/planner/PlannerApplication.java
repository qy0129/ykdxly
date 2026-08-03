package com.changlu.planner;

import com.changlu.planner.db.Database;
import com.changlu.planner.http.ApiServer;
import com.changlu.planner.wechat.WechatBotAgent;

public final class PlannerApplication {
  private PlannerApplication() {}

  public static void main(String[] args) throws Exception {
    Database database = Database.fromEnvironment();
    database.start();
    database.ensureDefaultContext();
    database.ensureWechatLoginTable();
    ApiServer server = new ApiServer(database, Integer.parseInt(System.getenv().getOrDefault("PLANNER_PORT", "8081")));
    server.start();
    WechatBotAgent wechatBot = new WechatBotAgent(database);
    if (Boolean.parseBoolean(System.getenv().getOrDefault("PLANNER_WECHAT_ENABLED", "true"))) {
      wechatBot.start();
    } else {
      System.out.println("Wechat Bot disabled by PLANNER_WECHAT_ENABLED=false");
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      wechatBot.close();
      server.stop();
      database.stop();
    }));
    System.out.println("Changlu Planner web listening on http://127.0.0.1:" + server.port() + "/");
  }
}

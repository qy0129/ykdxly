package com.changlu.planner.shared.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.util.Collections;

/**
 * 微信消息里使用的网页地址解析，统一微信端各入口（工作台、查看完整卡片、信息搜集表、简报、提醒）的链接规则。
 *
 * 显式配置 PLANNER_WEB_URL/web.url 时使用该值（开发端口 4173 自动映射到后端 8081）；
 * 未配置时回退到局域网地址，保证手机微信能访问到运行后端的那台电脑，而不是指向手机自身的 127.0.0.1。
 */
public final class WebUrlResolver {
  private WebUrlResolver() {}

  public static String resolve() {
    String configured = EnvironmentConfig.value("PLANNER_WEB_URL", "web.url", "");
    if (configured.isBlank()) return "http://" + lanAddress() + ":8081/";
    try {
      URI uri = URI.create(configured);
      if (uri.getPort() == 4173) {
        return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), 8081, uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
      }
    } catch (Exception ignored) { }
    return configured;
  }

  /** 探测本机在局域网内的 IPv4 地址：优先 Wi-Fi，其次以太网，最后任一非虚拟网卡。 */
  private static String lanAddress() {
    try {
      String fallback = "";
      String ethernet = "";
      for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!network.isUp() || network.isLoopback()) continue;
        String name = (network.getName() + " " + network.getDisplayName()).toLowerCase();
        if (name.contains("virtual") || name.contains("vethernet") || name.contains("hyper-v")
            || name.contains("vmware") || name.contains("virtualbox") || name.contains("docker") || name.contains("wsl")) continue;
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
          if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
          if (fallback.isBlank()) fallback = address.getHostAddress();
          if (name.contains("wi-fi") || name.contains("wifi") || name.contains("wlan") || name.contains("wireless")) return address.getHostAddress();
          if (ethernet.isBlank() && name.contains("ethernet")) ethernet = address.getHostAddress();
        }
      }
      if (!ethernet.isBlank()) return ethernet;
      if (!fallback.isBlank()) return fallback;
    } catch (Exception ignored) { }
    return "127.0.0.1";
  }
}

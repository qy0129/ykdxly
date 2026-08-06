package com.changlu.planner.agent.subagents.travel.services;

/** WGS84 to GCJ-02 conversion for browser coordinates entering AMap APIs. */
public final class Gcj02 {
  private static final double PI = Math.PI;
  private static final double A = 6378245.0;
  private static final double EE = 0.00669342162296594323;
  private Gcj02() {}

  public static Point fromWgs84(double lat, double lng) {
    if (outsideChina(lat, lng)) return new Point(lat, lng);
    double dLat = transformLat(lng - 105.0, lat - 35.0);
    double dLng = transformLng(lng - 105.0, lat - 35.0);
    double radLat = lat / 180.0 * PI;
    double magic = Math.sin(radLat);
    magic = 1 - EE * magic * magic;
    double sqrtMagic = Math.sqrt(magic);
    dLat = dLat * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
    dLng = dLng * 180.0 / (A / sqrtMagic * Math.cos(radLat) * PI);
    return new Point(lat + dLat, lng + dLng);
  }

  private static boolean outsideChina(double lat, double lng) {
    return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
  }
  private static double transformLat(double x, double y) {
    double value = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x));
    value += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
    value += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3;
    return value + (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
  }
  private static double transformLng(double x, double y) {
    double value = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x));
    value += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
    value += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3;
    return value + (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3;
  }
  public record Point(double lat, double lng) {}
}

import java.sql.*;
import java.util.*;

public class DbCheck {
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/changlu_planner?useSSL=false&characterEncoding=utf8", "root", "Aa350981")) {
      byte[] ws = uuid("00000000-0000-0000-0000-000000000002");
      byte[] usr = uuid("00000000-0000-0000-0000-000000000001");

      System.out.println("=== plans (workspace 0002) ===");
      try (PreparedStatement p = c.prepareStatement("SELECT id, title, status, deleted_at, created_at FROM plans WHERE workspace_id=? ORDER BY created_at DESC LIMIT 15")) {
        p.setBytes(1, ws);
        try (ResultSet rs = p.executeQuery()) { while (rs.next()) System.out.printf("id=%s | %s | status=%s | del=%s | created=%s%n", hex(rs.getBytes(1)), rs.getString(2), rs.getString(3), rs.getTimestamp(4), rs.getTimestamp(5)); }
      }

      System.out.println("=== learning_goals (user 0001) ===");
      try (PreparedStatement p = c.prepareStatement("SELECT id, title, plan_id, target_date, deleted_at, created_at FROM learning_goals WHERE user_id=? ORDER BY created_at DESC LIMIT 15")) {
        p.setBytes(1, usr);
        try (ResultSet rs = p.executeQuery()) { while (rs.next()) System.out.printf("id=%s | %s | plan_id=%s | target=%s | del=%s | created=%s%n", hex(rs.getBytes(1)), rs.getString(2), rs.getString(3)==null?null:hex(rs.getBytes(3)), rs.getDate(4), rs.getTimestamp(5), rs.getTimestamp(6)); }
      }

      System.out.println("=== plan_tasks (recent) ===");
      try (PreparedStatement p = c.prepareStatement("SELECT pt.id, pt.plan_id, pt.title, pt.due_at, pt.deleted_at FROM plan_tasks pt WHERE pt.plan_id IN (SELECT id FROM plans WHERE workspace_id=?) ORDER BY pt.created_at DESC LIMIT 10")) {
        p.setBytes(1, ws);
        try (ResultSet rs = p.executeQuery()) { while (rs.next()) System.out.printf("id=%s | plan=%s | %s | due=%s | del=%s%n", hex(rs.getBytes(1)), hex(rs.getBytes(2)), rs.getString(3), rs.getTimestamp(4), rs.getTimestamp(5)); }
      }

      System.out.println("=== schedule_items (recent) ===");
      try (PreparedStatement p = c.prepareStatement("SELECT id, title, start_at, deleted_at FROM schedule_items WHERE workspace_id=? ORDER BY created_at DESC LIMIT 10")) {
        p.setBytes(1, ws);
        try (ResultSet rs = p.executeQuery()) { while (rs.next()) System.out.printf("id=%s | %s | start=%s | del=%s%n", hex(rs.getBytes(1)), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4)); }
      }
    }
  }
  static byte[] uuid(String s) throws Exception {
    java.util.UUID u = java.util.UUID.fromString(s);
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16);
    b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
    return b.array();
  }
  static String hex(byte[] b) { if (b==null) return null; StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02X",x)); return sb.toString(); }
}

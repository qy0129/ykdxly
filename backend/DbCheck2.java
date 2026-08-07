import java.sql.*;
import java.nio.file.*;

public class DbCheck2 {
  static StringBuilder out = new StringBuilder();
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/changlu_planner?useSSL=false&characterEncoding=utf8", "root", "Aa350981")) {
      out.append("=== workspaces ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, name FROM workspaces")) {
        while (rs.next()) out.append("\n").append(hex(rs.getBytes(1))).append(" | ").append(rs.getString(2));
      }
      out.append("\n\n=== ALL plans ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT p.id, p.title, p.status, p.workspace_id, p.deleted_at, p.created_at FROM plans p ORDER BY p.created_at DESC LIMIT 25")) {
        while (rs.next()) out.append("\n").append(hex(rs.getBytes(1))).append(" | ").append(rs.getString(2)).append(" | status=").append(rs.getString(3)).append(" | ws=").append(hex(rs.getBytes(4))).append(" | del=").append(rs.getTimestamp(5)).append(" | created=").append(rs.getTimestamp(6));
      }
      out.append("\n\n=== ALL learning_goals ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT lg.id, lg.title, lg.plan_id, lg.user_id, lg.target_date, lg.deleted_at, lg.created_at FROM learning_goals lg ORDER BY lg.created_at DESC LIMIT 25")) {
        while (rs.next()) out.append("\n").append(hex(rs.getBytes(1))).append(" | ").append(rs.getString(2)).append(" | plan=").append(rs.getString(3)==null?null:hex(rs.getBytes(3))).append(" | user=").append(hex(rs.getBytes(4))).append(" | target=").append(rs.getDate(5)).append(" | del=").append(rs.getTimestamp(6)).append(" | created=").append(rs.getTimestamp(7));
      }
      out.append("\n\n=== ALL tables ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW TABLES")) {
        while (rs.next()) out.append("\n").append(rs.getString(1));
      }
    }
    Files.write(Paths.get("D:/.CC/backend/dbcheck2.txt"), out.toString().getBytes("UTF-8"));
  }
  static byte[] uuid(String s) throws Exception {
    java.util.UUID u = java.util.UUID.fromString(s);
    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16);
    b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits());
    return b.array();
  }
  static String hex(byte[] b) { if (b==null) return null; StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02X",x)); return sb.toString(); }
}

import java.sql.*;
import java.nio.file.*;

public class DbCheck3 {
  static StringBuilder out = new StringBuilder();
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/changlu_planner?useSSL=false&characterEncoding=utf8", "root", "Aa350981")) {
      out.append("=== users ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, external_id, display_name, created_at FROM users ORDER BY created_at")) {
        while (rs.next()) out.append("\n").append(hex(rs.getBytes(1))).append(" | ext=").append(rs.getString(2)).append(" | name=").append(rs.getString(3)).append(" | created=").append(rs.getTimestamp(4));
      }
      out.append("\n\n=== workspaces ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, owner_id, name, created_at FROM workspaces ORDER BY created_at")) {
        while (rs.next()) out.append("\n").append(hex(rs.getBytes(1))).append(" | owner=").append(hex(rs.getBytes(2))).append(" | ").append(rs.getString(3)).append(" | created=").append(rs.getTimestamp(4));
      }
      out.append("\n\n=== workspace_members ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT workspace_id, user_id, role FROM workspace_members")) {
        while (rs.next()) out.append("\nws=").append(hex(rs.getBytes(1))).append(" | user=").append(hex(rs.getBytes(2))).append(" | ").append(rs.getString(3));
      }
      byte[] ws = hexToBytes("E7830D6553E64E9FAC4A6E13EE38CAA4");
      for (String t : new String[]{"plans","todos","schedule_items","learning_goals"}) {
        out.append("\n--- ").append(t).append(" ---");
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM " + t + " WHERE workspace_id=?")) {
          p.setBytes(1, ws);
          try (ResultSet rs = p.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n=0;
            while (rs.next() && n<10) { out.append("\n"); for (int i=1;i<=md.getColumnCount();i++) { String v=rs.getString(i); out.append(md.getColumnName(i)).append("=").append(v!=null?v:"NULL").append(" "); } n++; }
            if (n==0) out.append("\n(empty)");
          }
        }
      }
      out.append("\n--- plan_tasks (joined via plans) ---");
      try (PreparedStatement p = c.prepareStatement("SELECT pt.title, pt.due_at, pt.deleted_at, pl.title FROM plan_tasks pt JOIN plans pl ON pt.plan_id=pl.id WHERE pl.workspace_id=? LIMIT 10")) {
        p.setBytes(1, ws);
        try (ResultSet rs = p.executeQuery()) { int n=0; while (rs.next()&&n<10) { out.append("\n").append(rs.getString(1)).append(" | due=").append(rs.getTimestamp(2)).append(" | del=").append(rs.getTimestamp(3)).append(" | plan=").append(rs.getString(4)); n++; } if(n==0) out.append("\n(empty)"); }
      }
      out.append("\n\n=== ai_channel_sessions (identity mapping) ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM ai_channel_sessions LIMIT 20")) {
        ResultSetMetaData md = rs.getMetaData();
        int n=0; while (rs.next() && n<20) { out.append("\n"); for (int i=1;i<=md.getColumnCount();i++) out.append(md.getColumnName(i)).append("=").append(rs.getString(i)!=null?rs.getString(i):"NULL").append(" "); n++; }
      }
      out.append("\n\n=== wechat_login_sessions ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM wechat_login_sessions LIMIT 10")) {
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) { out.append("\n"); for (int i=1;i<=md.getColumnCount();i++) out.append(md.getColumnName(i)).append("=").append(rs.getString(i)!=null?rs.getString(i):"NULL").append(" "); }
      }
    }
    Files.write(Paths.get("D:/.CC/backend/dbcheck3.txt"), out.toString().getBytes("UTF-8"));
  }
  static byte[] hexToBytes(String h){ byte[] b=new byte[h.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16); return b; }
  static String hex(byte[] b){ if(b==null)return null; StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02X",x)); return sb.toString(); }
}

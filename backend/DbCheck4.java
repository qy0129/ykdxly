import java.sql.*;
import java.nio.file.*;

public class DbCheck4 {
  static StringBuilder out = new StringBuilder();
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/changlu_planner?useSSL=false&characterEncoding=utf8", "root", "Aa350981")) {
      out.append("=== ai_action_drafts (recent 20, meta only) ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, workspace_id, user_id, status, source_channel, created_at, updated_at FROM ai_action_drafts ORDER BY created_at DESC LIMIT 20")) {
        while (rs.next()) out.append("\nid=").append(hex(rs.getBytes(1))).append(" | ws=").append(hex(rs.getBytes(2))).append(" | user=").append(hex(rs.getBytes(3))).append(" | ").append(rs.getString(4)).append(" | src=").append(rs.getString(5)).append(" | created=").append(rs.getTimestamp(6)).append(" | upd=").append(rs.getTimestamp(7));
      }
      out.append("\n\n=== draft details (latest 5) ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, request_text, LEFT(reply,500), actions, status, created_at FROM ai_action_drafts ORDER BY created_at DESC LIMIT 5")) {
        while (rs.next()) {
          out.append("\n--- id=").append(hex(rs.getBytes(1))).append(" | status=").append(rs.getString(5)).append(" | created=").append(rs.getTimestamp(6)).append("\nreq=").append(rs.getString(2)!=null?rs.getString(2):"NULL").append("\nreply=").append(rs.getString(3)!=null?rs.getString(3):"NULL").append("\nactions=").append(rs.getString(4)!=null?rs.getString(4):"NULL");
        }
      }
      out.append("\n\n=== ai_conversations ===");
      try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, workspace_id, user_id, source_channel, title, created_at, updated_at FROM ai_conversations ORDER BY updated_at DESC LIMIT 15")) {
        while (rs.next()) out.append("\nid=").append(hex(rs.getBytes(1))).append(" | ws=").append(hex(rs.getBytes(2))).append(" | user=").append(hex(rs.getBytes(3))).append(" | ").append(rs.getString(4)).append(" | ").append(rs.getString(5)!=null?rs.getString(5):"").append(" | created=").append(rs.getTimestamp(6)).append(" | upd=").append(rs.getTimestamp(7));
      }
      out.append("\n\n=== ai_messages (wechat, recent 30) ===");
      try (PreparedStatement p = c.prepareStatement("SELECT m.conversation_id, c.user_id, m.role, LEFT(m.content,200), m.created_at FROM ai_messages m JOIN ai_conversations c ON m.conversation_id=c.id WHERE c.source_channel='wechat' ORDER BY m.created_at DESC LIMIT 30")) {
        try (ResultSet rs = p.executeQuery()) {
          while (rs.next()) out.append("\nconv=").append(hex(rs.getBytes(1))).append(" | user=").append(hex(rs.getBytes(2))).append(" | ").append(rs.getString(3)).append(" | ").append(rs.getTimestamp(5)).append("\n  ").append(rs.getString(4)!=null?rs.getString(4):"NULL");
        }
      }
    }
    Files.write(Paths.get("D:/.CC/backend/dbcheck4.txt"), out.toString().getBytes("UTF-8"));
  }
  static byte[] hexToBytes(String h){ byte[] b=new byte[h.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16); return b; }
  static String hex(byte[] b){ if(b==null)return null; StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02X",x)); return sb.toString(); }
}

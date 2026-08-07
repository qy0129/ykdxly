import java.sql.*;
import java.nio.file.*;

public class DbCheck5 {
  static StringBuilder out = new StringBuilder();
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/changlu_planner?useSSL=false&characterEncoding=utf8", "root", "Aa350981")) {
      // drafts for user 0001 wechat
      out.append("=== drafts (user 0001, src=wechat) ===");
      try (PreparedStatement p = c.prepareStatement("SELECT id, conversation_id, status, request_text, created_at, confirmed_at, cancelled_at FROM ai_action_drafts WHERE user_id=? AND source_channel='wechat' ORDER BY created_at DESC LIMIT 10")) {
        p.setBytes(1, hexToBytes("00000000000000000000000000000001"));
        try (ResultSet rs = p.executeQuery()) {
          while (rs.next()) out.append("\nid=").append(hex(rs.getBytes(1))).append(" | conv=").append(hex(rs.getBytes(2))).append(" | ").append(rs.getString(3)).append(" | req=").append(rs.getString(4)!=null?rs.getString(4):"").append(" | created=").append(rs.getTimestamp(5)).append(" | conf=").append(rs.getTimestamp(6)).append(" | canc=").append(rs.getTimestamp(7));
        }
      }
      // messages in the D81E0F7E conversation
      out.append("\n\n=== conversation messages for draft D81E0F7E conv ===");
      try (PreparedStatement p = c.prepareStatement("SELECT c.id, m.id, m.role, LEFT(m.content,300), m.created_at FROM ai_conversations c JOIN ai_messages m ON m.conversation_id=c.id WHERE c.user_id=? AND c.source_channel='wechat' ORDER BY m.created_at ASC LIMIT 60")) {
        p.setBytes(1, hexToBytes("00000000000000000000000000000001"));
        try (ResultSet rs = p.executeQuery()) {
          while (rs.next()) out.append("\nconv=").append(hex(rs.getBytes(1))).append(" | ").append(rs.getString(3)).append(" | ").append(rs.getTimestamp(5)).append("\n  ").append(rs.getString(4)!=null?rs.getString(4):"NULL");
        }
      }
    }
    Files.write(Paths.get("D:/.CC/backend/dbcheck5.txt"), out.toString().getBytes("UTF-8"));
  }
  static byte[] hexToBytes(String h){ byte[] b=new byte[h.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(h.substring(i*2,i*2+2),16); return b; }
  static String hex(byte[] b){ if(b==null)return null; StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02X",x)); return sb.toString(); }
}

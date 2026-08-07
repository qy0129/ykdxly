import java.sql.*;
import java.nio.file.*;
public class DbSchema2 {
  static StringBuilder out = new StringBuilder();
  public static void main(String[] args) throws Exception {
    Class.forName("com.mysql.cj.jdbc.Driver");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/changlu_planner?useSSL=false&characterEncoding=utf8", "root", "Aa350981")) {
      for (String t : new String[]{"ai_action_drafts","ai_conversations","ai_messages"}) {
        out.append("\n=== ").append(t).append(" ===\n");
        try (Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SHOW COLUMNS FROM "+t)) {
          while (rs.next()) out.append(rs.getString(1)).append(" ").append(rs.getString(2)).append("\n");
        }
      }
    }
    Files.write(Paths.get("D:/.CC/backend/dbschema2.txt"), out.toString().getBytes("UTF-8"));
  }
}

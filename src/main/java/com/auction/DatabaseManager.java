package com.auction;

import org.mindrot.jbcrypt.BCrypt;
import java.sql.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DatabaseManager {

    private static final String DB_URL  = "jdbc:h2:./auction_v2;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final String DB_USER = "sa";
    private static final String DB_PASS = "";

    private Connection connection;

    public void connect() throws SQLException {
        connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
        System.out.println("[DB] Connected to H2 database (v2).");
        createTables();
        seedData();
    }

    private void createTables() throws SQLException {
        try (Statement s = connection.createStatement()) {

            s.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id            INT AUTO_INCREMENT PRIMARY KEY,
                    username      VARCHAR(50)  NOT NULL UNIQUE,
                    password_hash VARCHAR(255) NOT NULL,
                    role          VARCHAR(10)  NOT NULL DEFAULT 'BIDDER',
                    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
                )
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    token      VARCHAR(64)  PRIMARY KEY,
                    user_id    INT          NOT NULL,
                    username   VARCHAR(50)  NOT NULL,
                    role       VARCHAR(10)  NOT NULL,
                    created_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id)
                )
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS items (
                    id            INT AUTO_INCREMENT PRIMARY KEY,
                    name          VARCHAR(255) NOT NULL,
                    description   TEXT,
                    start_price   DOUBLE       NOT NULL,
                    current_bid   DOUBLE       NOT NULL,
                    top_bidder    VARCHAR(100) DEFAULT 'None',
                    status        VARCHAR(20)  DEFAULT 'OPEN',
                    end_time      TIMESTAMP    NOT NULL,
                    seller        VARCHAR(100) DEFAULT 'Admin',
                    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
                )
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS bids (
                    id        INT AUTO_INCREMENT PRIMARY KEY,
                    item_id   INT          NOT NULL,
                    bidder    VARCHAR(100) NOT NULL,
                    amount    DOUBLE       NOT NULL,
                    bid_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (item_id) REFERENCES items(id)
                )
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS chat_messages (
                    id         INT AUTO_INCREMENT PRIMARY KEY,
                    username   VARCHAR(100) NOT NULL,
                    role       VARCHAR(10)  NOT NULL,
                    message    TEXT         NOT NULL,
                    sent_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
                )
            """);

            System.out.println("[DB] All tables ready.");
        }
    }

    private void seedData() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        }

        String insertUser = "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertUser)) {
            Object[][] users = {
                    {"seller", BCrypt.hashpw("seller123", BCrypt.gensalt()), "SELLER"},
                    {"alice",  BCrypt.hashpw("alice123",  BCrypt.gensalt()), "BIDDER"},
                    {"bob",    BCrypt.hashpw("bob123",    BCrypt.gensalt()), "BIDDER"},
                    {"guest",  BCrypt.hashpw("guest123",  BCrypt.gensalt()), "BIDDER"},
            };
            for (Object[] u : users) {
                ps.setString(1, (String) u[0]);
                ps.setString(2, (String) u[1]);
                ps.setString(3, (String) u[2]);
                ps.executeUpdate();
            }
        }

        String insertItem = """
            INSERT INTO items (name, description, start_price, current_bid, end_time, seller)
            VALUES (?, ?, ?, ?, DATEADD('MINUTE', ?, CURRENT_TIMESTAMP), 'seller')
        """;
        try (PreparedStatement ps = connection.prepareStatement(insertItem)) {
            Object[][] items = {
                    {"Vintage Rolex Watch",       "1965 Submariner in excellent condition",       2000.0,  2000.0,  60},
                    {"MacBook Pro 16-inch",       "M3 Max, 64GB RAM, barely used",               1800.0,  1800.0,  90},
                    {"Original Oil Painting",     "Abstract landscape, 24x36 inches",             500.0,   500.0,  45},
                    {"Rare First-Edition Book",   "Signed copy of a bestselling novel",           300.0,   300.0, 120},
                    {"Gaming PC Bundle",          "RTX 4090, i9, 32GB, full setup",             3000.0,  3000.0,  75},
                    {"Canon EOS R5 Camera",       "50MP mirrorless, dual card slots, 8K video", 3500.0,  3500.0,  50},
                    {"Antique Writing Desk",      "Victorian mahogany, original brass fittings",  800.0,   800.0, 100},
                    {"Gibson Les Paul Guitar",    "1959 reissue, sunburst finish, hard case",    2500.0,  2500.0,  55},
                    {"Signed Sports Jersey",      "Match-worn, certified authentication",         450.0,   450.0,  80},
                    {"Diamond Pendant Necklace",  "1.2ct VS1 clarity, 18k white gold",          1200.0,  1200.0,  65},
                    {"Drone — DJI Mavic 3 Pro",  "Triple camera, 43-min flight time, sealed",   1600.0,  1600.0,  40},
                    {"Ceramic Pottery Collection","Hand-thrown stoneware, set of 6 pieces",       250.0,   250.0, 110},
            };
            for (Object[] row : items) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setDouble(3, (Double) row[2]);
                ps.setDouble(4, (Double) row[3]);
                ps.setInt(5,    (Integer) row[4]);
                ps.executeUpdate();
            }
        }

        System.out.println("[DB] Default users and items seeded.");
        System.out.println("[DB] Login: seller/seller123  alice/alice123  bob/bob123  guest/guest123");
    }

    public String login(String username, String password) throws SQLException {
        String sql = "SELECT id, password_hash, role FROM users WHERE username = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String hash = rs.getString("password_hash");
                if (!BCrypt.checkpw(password, hash)) return null;

                String token = generateToken();
                String insertSession = "INSERT INTO sessions (token, user_id, username, role) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps2 = connection.prepareStatement(insertSession)) {
                    ps2.setString(1, token);
                    ps2.setInt(2,    rs.getInt("id"));
                    ps2.setString(3, username);
                    ps2.setString(4, rs.getString("role"));
                    ps2.executeUpdate();
                }
                return token;
            }
        }
    }

    public String register(String username, String password, String role) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return "Username already taken.";
            }
        }
        String safeRole = "SELLER".equals(role) ? "SELLER" : "BIDDER";
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password_hash, role) VALUES (?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, safeRole);
            ps.executeUpdate();
        }
        return null;
    }

    public Map<String, String> getSession(String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        String sql = "SELECT username, role FROM sessions WHERE token = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, String> session = new HashMap<>();
                session.put("username", rs.getString("username"));
                session.put("role",     rs.getString("role"));
                return session;
            }
        }
    }

    public void logout(String token) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> getAllItems() throws SQLException {
        List<Map<String, Object>> items = new ArrayList<>();
        String sql = "SELECT *, DATEDIFF('SECOND', CURRENT_TIMESTAMP, end_time) AS seconds_left FROM items "
                + "WHERE status = 'OPEN' OR DATEDIFF('HOUR', end_time, CURRENT_TIMESTAMP) < 24 "
                + "ORDER BY id";
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) items.add(rowToMap(rs));
        }
        return items;
    }

    public Map<String, Object> getItem(int itemId) throws SQLException {
        String sql = "SELECT *, DATEDIFF('SECOND', CURRENT_TIMESTAMP, end_time) AS seconds_left FROM items WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
            }
        }
        return null;
    }

    public List<Map<String, Object>> getItemsBySeller(String seller) throws SQLException {
        List<Map<String, Object>> items = new ArrayList<>();
        String sql = "SELECT *, DATEDIFF('SECOND', CURRENT_TIMESTAMP, end_time) AS seconds_left FROM items "
                + "WHERE seller = ? AND (status = 'OPEN' OR DATEDIFF('HOUR', end_time, CURRENT_TIMESTAMP) < 24) "
                + "ORDER BY id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, seller);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(rowToMap(rs));
            }
        }
        return items;
    }

    public void addItem(String name, String description, double startPrice,
                        int durationMinutes, String seller) throws SQLException {
        String sql = """
            INSERT INTO items (name, description, start_price, current_bid, end_time, seller)
            VALUES (?, ?, ?, ?, DATEADD('MINUTE', ?, CURRENT_TIMESTAMP), ?)
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setDouble(3, startPrice);
            ps.setDouble(4, startPrice);
            ps.setInt(5,    durationMinutes);
            ps.setString(6, seller);
            ps.executeUpdate();
        }
    }

    public synchronized boolean placeBid(int itemId, String bidder, double amount) throws SQLException {
        Map<String, Object> item = getItem(itemId);
        if (item == null) return false;
        if (!"OPEN".equals(item.get("status"))) return false;

        long secondsLeft = ((Number) item.get("seconds_left")).longValue();
        if (secondsLeft <= 0) return false;

        double currentBid = ((Number) item.get("current_bid")).doubleValue();
        if (amount <= currentBid) return false;

        String updateSql = "UPDATE items SET current_bid = ?, top_bidder = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setDouble(1, amount);
            ps.setString(2, bidder);
            ps.setInt(3,    itemId);
            ps.executeUpdate();
        }

        if (secondsLeft < 30) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE items SET end_time = DATEADD('MINUTE', 2, end_time) WHERE id = ?")) {
                ps.setInt(1, itemId);
                ps.executeUpdate();
                System.out.println("[DB] Snipe protection triggered on item #" + itemId + " — extended 2 min");
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bids (item_id, bidder, amount) VALUES (?, ?, ?)")) {
            ps.setInt(1,    itemId);
            ps.setString(2, bidder);
            ps.setDouble(3, amount);
            ps.executeUpdate();
        }

        return true;
    }

    public synchronized void closeItem(int itemId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE items SET status = 'CLOSED' WHERE id = ?")) {
            ps.setInt(1, itemId);
            ps.executeUpdate();
        }
    }

    public synchronized List<Integer> closeExpiredItems() throws SQLException {
        List<Integer> closed = new ArrayList<>();
        String sql = "SELECT id FROM items WHERE status = 'OPEN' AND end_time <= CURRENT_TIMESTAMP";
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) closed.add(rs.getInt("id"));
        }
        for (int id : closed) closeItem(id);
        return closed;
    }

    public List<Map<String, Object>> getBidHistory(int itemId) throws SQLException {
        List<Map<String, Object>> bids = new ArrayList<>();
        String sql = "SELECT * FROM bids WHERE item_id = ? ORDER BY bid_time DESC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("bidder",   rs.getString("bidder"));
                    row.put("amount",   rs.getDouble("amount"));
                    row.put("bid_time", rs.getString("bid_time"));
                    bids.add(row);
                }
            }
        }
        return bids;
    }

    public void saveChat(String username, String role, String message) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO chat_messages (username, role, message) VALUES (?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, role);
            ps.setString(3, message);
            ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> getRecentChat() throws SQLException {
        List<Map<String, Object>> msgs = new ArrayList<>();
        String sql = "SELECT * FROM (SELECT * FROM chat_messages ORDER BY sent_at DESC LIMIT 50) ORDER BY sent_at ASC";
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("username", rs.getString("username"));
                m.put("role",     rs.getString("role"));
                m.put("message",  rs.getString("message"));
                m.put("sent_at",  rs.getString("sent_at"));
                msgs.add(m);
            }
        }
        return msgs;
    }

    public Map<String, Object> getUserStats(String username) throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("username", username);

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM bids WHERE bidder = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); stats.put("total_bids", rs.getInt(1));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM bids WHERE bidder = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); stats.put("total_spent_bidding", rs.getDouble(1));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM items WHERE top_bidder = ? AND status = 'CLOSED'")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); stats.put("items_won", rs.getInt(1));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(current_bid), 0) FROM items WHERE top_bidder = ? AND status = 'CLOSED'")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); stats.put("total_won_value", rs.getDouble(1));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(DISTINCT item_id) FROM bids WHERE bidder = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); stats.put("items_bid_on", rs.getInt(1));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(MAX(amount), 0) FROM bids WHERE bidder = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); stats.put("highest_bid", rs.getDouble(1));
            }
        }

        List<Map<String, Object>> recent = new ArrayList<>();
        String sql = """
            SELECT b.amount, b.bid_time, i.name AS item_name, i.status, i.top_bidder
            FROM bids b JOIN items i ON b.item_id = i.id
            WHERE b.bidder = ? ORDER BY b.bid_time DESC LIMIT 10
        """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("item_name",  rs.getString("item_name"));
                    row.put("amount",     rs.getDouble("amount"));
                    row.put("bid_time",   rs.getString("bid_time"));
                    row.put("status",     rs.getString("status"));
                    row.put("won",        username.equals(rs.getString("top_bidder")) &&
                            "CLOSED".equals(rs.getString("status")));
                    recent.add(row);
                }
            }
        }
        stats.put("recent_bids", recent);

        return stats;
    }

    public Map<String, Object> getSellerStats(String seller) throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM items WHERE seller = ?")) {
            ps.setString(1, seller);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); stats.put("total_items", rs.getInt(1)); }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM items WHERE seller = ? AND status = 'OPEN'")) {
            ps.setString(1, seller);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); stats.put("open_items", rs.getInt(1)); }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM items WHERE seller = ? AND status = 'CLOSED'")) {
            ps.setString(1, seller);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); stats.put("closed_items", rs.getInt(1)); }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(current_bid),0) FROM items WHERE seller=? AND status='CLOSED'")) {
            ps.setString(1, seller);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); stats.put("total_revenue", rs.getDouble(1)); }
        }
        return stats;
    }

    public String exportResultsToFile() throws SQLException, IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename  = "auction_results_" + timestamp + ".txt";

        try (BufferedWriter w = new BufferedWriter(new FileWriter(filename))) {
            w.write("========================================"); w.newLine();
            w.write("   AUCTION RESULTS REPORT v2");          w.newLine();
            w.write("   Generated: " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))); w.newLine();
            w.write("========================================"); w.newLine();

            for (Map<String, Object> item : getAllItems()) {
                int id = ((Number) item.get("id")).intValue();
                w.newLine();
                w.write("ITEM #" + id + ": " + item.get("name")); w.newLine();
                w.write("  Seller      : " + item.get("seller")); w.newLine();
                w.write("  Description : " + item.get("description")); w.newLine();
                w.write("  Start Price : $" + String.format("%.2f", ((Number)item.get("start_price")).doubleValue())); w.newLine();
                w.write("  Final Bid   : $" + String.format("%.2f", ((Number)item.get("current_bid")).doubleValue())); w.newLine();
                w.write("  Winner      : " + item.get("top_bidder")); w.newLine();
                w.write("  Status      : " + item.get("status")); w.newLine();

                List<Map<String, Object>> history = getBidHistory(id);
                if (history.isEmpty()) { w.write("  Bids        : (none)"); w.newLine(); }
                else {
                    w.write("  Bid History (" + history.size() + " bids):"); w.newLine();
                    for (Map<String, Object> bid : history) {
                        w.write(String.format("    - %-20s $%.2f  at %s",
                                bid.get("bidder"), ((Number)bid.get("amount")).doubleValue(), bid.get("bid_time")));
                        w.newLine();
                    }
                }
                w.write("  ----------------------------------------"); w.newLine();
            }
            w.write("END OF REPORT"); w.newLine();
        }
        System.out.println("[FILE] Exported: " + filename);
        return filename;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           rs.getInt("id"));
        m.put("name",         rs.getString("name"));
        m.put("description",  rs.getString("description"));
        m.put("start_price",  rs.getDouble("start_price"));
        m.put("current_bid",  rs.getDouble("current_bid"));
        m.put("top_bidder",   rs.getString("top_bidder"));
        m.put("status",       rs.getString("status"));
        m.put("seller",       rs.getString("seller"));
        m.put("end_time",     rs.getString("end_time"));
        try { m.put("seconds_left", Math.max(0, rs.getLong("seconds_left"))); }
        catch (SQLException e) { m.put("seconds_left", 0L); }
        return m;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public void close() {
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException e) { e.printStackTrace(); }
    }
}

package com.rmichat.database;

import com.rmichat.common.ChatMessage;

import java.io.File;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Quản lý Cơ sở dữ liệu SQLite nhúng cho từng Server.
 * Mỗi Server sở hữu 1 file database riêng biệt (.db) lưu trữ:
 *  1. Bảng messages: Lịch sử tin nhắn toàn hệ thống
 *  2. Bảng user_activity: Lịch sử ra / vào, đăng nhập / đăng xuất của người dùng
 */
public class DatabaseManager {

    private final String dbUrl;

    public static class ActivityLog {
        private final int id;
        private final String username;
        private final String action;
        private final String serverId;
        private final long timestamp;

        public ActivityLog(int id, String username, String action, String serverId, long timestamp) {
            this.id = id;
            this.username = username;
            this.action = action;
            this.serverId = serverId;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public String getUsername() { return username; }
        public String getAction() { return action; }
        public String getServerId() { return serverId; }
        public long getTimestamp() { return timestamp; }

        public String getFormattedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(new Date(timestamp));
        }
    }

    public DatabaseManager(String serverId) {
        this(serverId, System.getProperty("rmichat.db.dir", "database"));
    }

    public DatabaseManager(String serverId, String baseDir) {
        File dbDir = new File(baseDir);
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }

        // Tên file CSDL: baseDir/server1_chat.db hoặc baseDir/server2_chat.db
        String sanitizedName = serverId.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (sanitizedName.isEmpty()) sanitizedName = "server";
        String dbPath = baseDir + "/" + sanitizedName + "_chat.db";
        this.dbUrl = "jdbc:sqlite:" + dbPath;

        initDatabase();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initDatabase() {
        String createMessagesTable = """
            CREATE TABLE IF NOT EXISTS messages (
                message_id TEXT PRIMARY KEY,
                sender TEXT NOT NULL,
                recipient TEXT,
                content TEXT NOT NULL,
                type TEXT NOT NULL,
                origin_server TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            );
        """;

        String createActivityTable = """
            CREATE TABLE IF NOT EXISTS user_activity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                action TEXT NOT NULL,
                server_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            );
        """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesTable);
            stmt.execute(createActivityTable);
            // Nâng cấp bảng nếu DB đã tồn tại từ trước mà chưa có cột recipient
            try {
                stmt.execute("ALTER TABLE messages ADD COLUMN recipient TEXT;");
            } catch (SQLException ignored) {}
            System.out.println("[DATABASE] Khởi tạo thành công CSDL SQLite: " + dbUrl);
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Không thể khởi tạo bảng: " + e.getMessage());
        }
    }

    /**
     * Lưu tin nhắn vào SQLite (nếu chưa tồn tại).
     */
    public synchronized void saveMessage(ChatMessage msg) {
        if (msg == null) return;
        String sql = "INSERT OR IGNORE INTO messages(message_id, sender, recipient, content, type, origin_server, timestamp) VALUES(?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, msg.getMessageId());
            pstmt.setString(2, msg.getSender());
            pstmt.setString(3, msg.getRecipient());
            pstmt.setString(4, msg.getContent());
            pstmt.setString(5, msg.getType().name());
            pstmt.setString(6, msg.getOriginServer());
            pstmt.setLong(7, msg.getTimestamp());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Lưu tin nhắn thất bại: " + e.getMessage());
        }
    }

    /**
     * Lấy toàn bộ tin nhắn từ SQLite, sắp xếp theo thời gian tăng dần.
     */
    public synchronized List<ChatMessage> getAllMessages() {
        List<ChatMessage> list = new ArrayList<>();
        String sql = "SELECT message_id, sender, recipient, content, type, origin_server, timestamp FROM messages ORDER BY timestamp ASC";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("message_id");
                String sender = rs.getString("sender");
                String recipient = rs.getString("recipient");
                String content = rs.getString("content");
                String typeStr = rs.getString("type");
                String origin = rs.getString("origin_server");
                long ts = rs.getLong("timestamp");

                ChatMessage.MessageType type = ChatMessage.MessageType.CHAT;
                try {
                    type = ChatMessage.MessageType.valueOf(typeStr);
                } catch (Exception ignored) {}

                ChatMessage msg = new ChatMessage(id, sender, recipient, content, type, origin, ts);
                list.add(msg);
            }
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Đọc tin nhắn thất bại: " + e.getMessage());
        }
        return list;
    }

    /**
     * Ghi nhận lịch sử hoạt động của người dùng (Đăng nhập, Đăng xuất, Bị kick).
     */
    public synchronized void logActivity(String username, String action, String serverId) {
        String sql = "INSERT INTO user_activity(username, action, server_id, timestamp) VALUES(?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, action);
            pstmt.setString(3, serverId);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Ghi nhật ký hoạt động thất bại: " + e.getMessage());
        }
    }

    /**
     * Lấy toàn bộ nhật ký hoạt động người dùng (mới nhất lên đầu).
     */
    public synchronized List<ActivityLog> getAllActivityLogs() {
        List<ActivityLog> list = new ArrayList<>();
        String sql = "SELECT id, username, action, server_id, timestamp FROM user_activity ORDER BY id DESC LIMIT 200";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ActivityLog(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getString("action"),
                        rs.getString("server_id"),
                        rs.getLong("timestamp")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Đọc nhật ký hoạt động thất bại: " + e.getMessage());
        }
        return list;
    }

    public synchronized int getMessageCount() {
        String sql = "SELECT COUNT(*) FROM messages";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException ignored) {}
        return 0;
    }

    /**
     * Xóa toàn bộ dữ liệu (chỉ dùng cho Unit Test hoặc Reset hệ thống)
     */
    public synchronized void clearAllData() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM messages");
            stmt.executeUpdate("DELETE FROM user_activity");
        } catch (SQLException e) {
            System.err.println("[DATABASE] Xóa dữ liệu thất bại: " + e.getMessage());
        }
    }

    public String getDbUrl() {
        return dbUrl;
    }
}

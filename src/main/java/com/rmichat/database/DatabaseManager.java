package com.rmichat.database;

import com.rmichat.common.ChatGroup;
import com.rmichat.common.ChatMessage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

/**
 * Quản lý Cơ sở dữ liệu SQLite nhúng cho từng Server.
 * Mỗi Server sở hữu 1 file database riêng biệt (.db) lưu trữ:
 *  1. Bảng messages: Lịch sử tin nhắn toàn hệ thống (chat chung, riêng, nhóm, media)
 *  2. Bảng user_activity: Lịch sử ra / vào, đăng nhập / đăng xuất của người dùng
 *  3. Bảng chat_groups: Danh sách nhóm chat
 *  4. Bảng group_members: Danh sách thành viên trong từng nhóm chat
 */
public class DatabaseManager {

    private final String dbUrl;
    private final String serverId;
    private final File uploadsDir;

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
        this.serverId = serverId;
        File dbDir = new File(baseDir);
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }

        // Thư mục lưu trữ tệp tin vật lý
        String sanitizedName = serverId.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (sanitizedName.isEmpty()) sanitizedName = "server";

        this.uploadsDir = new File(baseDir + "/uploads_" + sanitizedName);
        if (!uploadsDir.exists()) {
            uploadsDir.mkdirs();
        }

        // Tên file CSDL: baseDir/server1_chat.db hoặc baseDir/server2_chat.db
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
                group_id TEXT,
                group_name TEXT,
                content TEXT NOT NULL,
                type TEXT NOT NULL,
                file_name TEXT,
                file_size INTEGER DEFAULT 0,
                file_path TEXT,
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

        String createGroupsTable = """
            CREATE TABLE IF NOT EXISTS chat_groups (
                group_id TEXT PRIMARY KEY,
                group_name TEXT NOT NULL,
                creator TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
        """;

        String createGroupMembersTable = """
            CREATE TABLE IF NOT EXISTS group_members (
                group_id TEXT NOT NULL,
                username TEXT NOT NULL,
                joined_at INTEGER NOT NULL,
                PRIMARY KEY (group_id, username)
            );
        """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesTable);
            stmt.execute(createActivityTable);
            stmt.execute(createGroupsTable);
            stmt.execute(createGroupMembersTable);

            // Nâng cấp bảng nếu DB đã tồn tại từ các phiên bản trước
            try { stmt.execute("ALTER TABLE messages ADD COLUMN recipient TEXT;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE messages ADD COLUMN group_id TEXT;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE messages ADD COLUMN group_name TEXT;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE messages ADD COLUMN file_name TEXT;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE messages ADD COLUMN file_size INTEGER DEFAULT 0;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE messages ADD COLUMN file_path TEXT;"); } catch (SQLException ignored) {}

            System.out.println("[DATABASE] Khởi tạo thành công CSDL SQLite: " + dbUrl);
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Không thể khởi tạo bảng: " + e.getMessage());
        }
    }

    /**
     * Lưu tin nhắn vào SQLite (nếu chưa tồn tại).
     * Nếu tin nhắn có đính kèm file/ảnh/audio, lưu file vật lý xuống đĩa cứng.
     */
    public synchronized void saveMessage(ChatMessage msg) {
        if (msg == null) return;

        String savedFilePath = null;
        if (msg.getFileData() != null && msg.getFileData().length > 0) {
            String safeFileName = msg.getFileName() != null ? msg.getFileName() : "file.bin";
            safeFileName = safeFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            File diskFile = new File(uploadsDir, msg.getMessageId() + "_" + safeFileName);
            try {
                Files.write(diskFile.toPath(), msg.getFileData());
                savedFilePath = diskFile.getAbsolutePath();
            } catch (IOException e) {
                System.err.println("[DATABASE LỖI] Lưu tệp vật lý thất bại: " + e.getMessage());
            }
        }

        String sql = "INSERT OR IGNORE INTO messages(message_id, sender, recipient, group_id, group_name, content, type, file_name, file_size, file_path, origin_server, timestamp) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, msg.getMessageId());
            pstmt.setString(2, msg.getSender());
            pstmt.setString(3, msg.getRecipient());
            pstmt.setString(4, msg.getGroupId());
            pstmt.setString(5, msg.getGroupName());
            pstmt.setString(6, msg.getContent());
            pstmt.setString(7, msg.getType().name());
            pstmt.setString(8, msg.getFileName());
            pstmt.setLong(9, msg.getFileSize());
            pstmt.setString(10, savedFilePath);
            pstmt.setString(11, msg.getOriginServer());
            pstmt.setLong(12, msg.getTimestamp());
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
        String sql = "SELECT message_id, sender, recipient, group_id, group_name, content, type, file_name, file_size, file_path, origin_server, timestamp FROM messages ORDER BY timestamp ASC";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String id = rs.getString("message_id");
                String sender = rs.getString("sender");
                String recipient = rs.getString("recipient");
                String groupId = rs.getString("group_id");
                String groupName = rs.getString("group_name");
                String content = rs.getString("content");
                String typeStr = rs.getString("type");
                String fileName = rs.getString("file_name");
                long fileSize = rs.getLong("file_size");
                String filePath = rs.getString("file_path");
                String origin = rs.getString("origin_server");
                long ts = rs.getLong("timestamp");

                ChatMessage.MessageType type = ChatMessage.MessageType.CHAT;
                try {
                    type = ChatMessage.MessageType.valueOf(typeStr);
                } catch (Exception ignored) {}

                byte[] fileBytes = null;
                if (filePath != null) {
                    File f = new File(filePath);
                    if (f.exists()) {
                        try {
                            fileBytes = Files.readAllBytes(f.toPath());
                        } catch (IOException ignored) {}
                    }
                }

                ChatMessage msg = new ChatMessage(id, sender, recipient, groupId, groupName, content, type, origin, ts, fileBytes, fileName, fileSize);
                list.add(msg);
            }
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Đọc tin nhắn thất bại: " + e.getMessage());
        }
        return list;
    }

    // =========================================================================
    // QUẢN LÝ NHÓM CHAT (GROUP CHAT CRUD)
    // =========================================================================

    /**
     * Lưu hoặc cập nhật một Nhóm chat vào CSDL.
     */
    public synchronized void saveGroup(ChatGroup group) {
        if (group == null) return;
        String sqlGroup = "INSERT OR REPLACE INTO chat_groups(group_id, group_name, creator, created_at) VALUES(?, ?, ?, ?)";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement pstmt = conn.prepareStatement(sqlGroup)) {
                pstmt.setString(1, group.getGroupId());
                pstmt.setString(2, group.getGroupName());
                pstmt.setString(3, group.getCreator());
                pstmt.setLong(4, group.getCreatedAt());
                pstmt.executeUpdate();
            }

            // Lưu danh sách thành viên
            String sqlMember = "INSERT OR IGNORE INTO group_members(group_id, username, joined_at) VALUES(?, ?, ?)";
            try (PreparedStatement pstmtMember = conn.prepareStatement(sqlMember)) {
                for (String member : group.getMembers()) {
                    pstmtMember.setString(1, group.getGroupId());
                    pstmtMember.setString(2, member);
                    pstmtMember.setLong(3, System.currentTimeMillis());
                    pstmtMember.addBatch();
                }
                pstmtMember.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Lưu nhóm chat thất bại: " + e.getMessage());
        }
    }

    /**
     * Thêm thành viên vào nhóm chat.
     */
    public synchronized void addGroupMember(String groupId, String username) {
        String sql = "INSERT OR IGNORE INTO group_members(group_id, username, joined_at) VALUES(?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, groupId);
            pstmt.setString(2, username);
            pstmt.setLong(3, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Thêm thành viên nhóm thất bại: " + e.getMessage());
        }
    }

    /**
     * Xóa thành viên khỏi nhóm chat.
     */
    public synchronized void removeGroupMember(String groupId, String username) {
        String sql = "DELETE FROM group_members WHERE group_id = ? AND username = ?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, groupId);
            pstmt.setString(2, username);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Xóa thành viên nhóm thất bại: " + e.getMessage());
        }
    }

    /**
     * Lấy toàn bộ nhóm chat và danh sách thành viên từ CSDL.
     */
    public synchronized List<ChatGroup> getAllGroups() {
        Map<String, ChatGroup> map = new LinkedHashMap<>();
        String sqlGroups = "SELECT group_id, group_name, creator, created_at FROM chat_groups ORDER BY created_at ASC";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlGroups)) {
            while (rs.next()) {
                String gid = rs.getString("group_id");
                String gname = rs.getString("group_name");
                String creator = rs.getString("creator");
                long cat = rs.getLong("created_at");
                map.put(gid, new ChatGroup(gid, gname, creator, cat));
            }
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Đọc danh sách nhóm thất bại: " + e.getMessage());
        }

        // Nạp danh sách thành viên cho từng nhóm
        String sqlMembers = "SELECT group_id, username FROM group_members";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sqlMembers)) {
            while (rs.next()) {
                String gid = rs.getString("group_id");
                String user = rs.getString("username");
                ChatGroup g = map.get(gid);
                if (g != null) {
                    g.addMember(user);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DATABASE LỖI] Đọc thành viên nhóm thất bại: " + e.getMessage());
        }

        return new ArrayList<>(map.values());
    }

    // =========================================================================
    // AUDIT LOG & UTILS
    // =========================================================================

    /**
     * Ghi nhận lịch sử hoạt động của người dùng (Đăng nhập, Đăng xuất, Bị kick, Tạo nhóm...).
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
            stmt.executeUpdate("DELETE FROM chat_groups");
            stmt.executeUpdate("DELETE FROM group_members");
        } catch (SQLException e) {
            System.err.println("[DATABASE] Xóa dữ liệu thất bại: " + e.getMessage());
        }
    }

    public String getDbUrl() {
        return dbUrl;
    }
}

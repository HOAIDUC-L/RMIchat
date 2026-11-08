package com.rmichat.server;

import com.rmichat.common.ChatMessage;
import com.rmichat.database.DatabaseManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Giao diện Trang Quản Trị Server (Server Admin GUI Dashboard).
 * Cho phép Quản trị viên và Giảng viên theo dõi trực quan:
 *  - Trạng thái liên kết P2P giữa 2 Server
 *  - Danh sách người dùng online ở cả 2 Server
 *  - Lịch sử ra/vào (Audit Logs)
 *  - Nhật ký gói tin đồng bộ và CHỨNG MINH THUẬT TOÁN CHỐNG LẶP VÔ HẠN
 *  - Dữ liệu lưu trữ trong CSDL SQLite
 *  - Các quyền Admin: Kick user, phát thông báo khẩn toàn hệ thống.
 */
public class ServerAdminGUI extends JFrame implements ServerEventListener {

    private final ChatServerImpl server;
    private final DatabaseManager dbManager;

    // Header Components
    private JLabel lblServerTitle;
    private JLabel lblPartnerStatus;
    private JLabel lblStatUsers;
    private JLabel lblStatMessages;
    private JLabel lblStatSync;

    // Tab 1: Online Users
    private DefaultTableModel modelOnlineUsers;
    private JTable tblOnlineUsers;

    // Tab 2: Activity Logs
    private DefaultTableModel modelActivityLogs;
    private JTable tblActivityLogs;

    // Tab 3: Sync Monitor (Ghi nhận đồng bộ & Chống lặp vô hạn)
    private DefaultTableModel modelSyncLogs;
    private JTable tblSyncLogs;

    // Tab 4: SQLite Database Viewer
    private DefaultTableModel modelDbMessages;
    private JTable tblDbMessages;

    // Bottom Bar
    private JTextField txtAdminNotice;
    private JButton btnSendNotice;
    private JButton btnForceSync;

    private int syncPacketCount = 0;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public ServerAdminGUI(ChatServerImpl server) {
        super(String.format("TRANG QUẢN TRỊ SERVER - [%s] (Cổng: %d)", server.getServerId(), server.getLocalPort()));
        this.server = server;
        this.dbManager = server.getDatabaseManager();

        // Đăng ký nhận sự kiện từ ChatServerImpl
        server.addEventListener(this);

        initUI();
        refreshAllData();
    }

    private void initUI() {
        setSize(1100, 720);
        setMinimumSize(new Dimension(950, 600));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 1. TOP: Header và Thẻ thống kê
        mainPanel.add(createHeaderPanel(), BorderLayout.NORTH);

        // 2. CENTER: Các Tab quản trị
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("Segoe UI", Font.BOLD, 13));

        tabbedPane.addTab("👥 Người dùng Online", createOnlineUsersTab());
        tabbedPane.addTab("📜 Lịch sử Ra / Vào (Audit Logs)", createActivityLogsTab());
        tabbedPane.addTab("🔄 Giám sát Đồng bộ & Chống lặp", createSyncMonitorTab());
        tabbedPane.addTab("💾 CSDL SQLite (Messages)", createDatabaseTab());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // 3. BOTTOM: Thanh tác vụ Admin
        mainPanel.add(createBottomActionPanel(), BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel(new BorderLayout(10, 6));
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
                new EmptyBorder(10, 12, 10, 12)
        ));
        headerPanel.setBackground(Color.WHITE);

        // Bên trái: Thông tin Server này
        JPanel infoLeft = new JPanel(new GridLayout(2, 1, 2, 2));
        infoLeft.setOpaque(false);
        lblServerTitle = new JLabel("🖥️ " + server.getServerId().toUpperCase() + "  [ĐANG HOẠT ĐỘNG]");
        lblServerTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        lblServerTitle.setForeground(new Color(0, 102, 204));

        JLabel lblServerDetails = new JLabel(String.format("Cổng Registry: %d  |  Mục tiêu đối tác: %s:%d  |  CSDL: %s",
                server.getLocalPort(), server.getPartnerHost(), server.getPartnerPort(), dbManager.getDbUrl()));
        lblServerDetails.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblServerDetails.setForeground(Color.GRAY);

        infoLeft.add(lblServerTitle);
        infoLeft.add(lblServerDetails);

        // Giữa: Trạng thái Server đối tác
        JPanel infoCenter = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 8));
        infoCenter.setOpaque(false);
        lblPartnerStatus = new JLabel("Đang dò tìm Server đối tác...");
        updatePartnerBadge(server.isPartnerConnected(), server.getPartnerHost() + ":" + server.getPartnerPort());

        // Bên phải: 3 Thẻ thống kê
        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        statsPanel.setOpaque(false);

        lblStatUsers = createStatBadge("Online", "0", new Color(40, 167, 69));
        lblStatMessages = createStatBadge("Tin nhắn", "0", new Color(0, 123, 255));
        lblStatSync = createStatBadge("Đồng bộ", "0", new Color(253, 126, 20));

        statsPanel.add(lblStatUsers);
        statsPanel.add(lblStatMessages);
        statsPanel.add(lblStatSync);

        headerPanel.add(infoLeft, BorderLayout.WEST);
        headerPanel.add(infoCenter, BorderLayout.CENTER);
        headerPanel.add(statsPanel, BorderLayout.EAST);

        return headerPanel;
    }

    private JLabel createStatBadge(String title, String val, Color color) {
        JLabel l = new JLabel(String.format("<html><center><small>%s</small><br><b><font size='+1'>%s</font></b></center></html>", title, val), SwingConstants.CENTER);
        l.setPreferredSize(new Dimension(80, 42));
        l.setOpaque(true);
        l.setBackground(new Color(color.getRed(), color.getGreen(), color.getBlue(), 25));
        l.setForeground(color);
        l.setBorder(BorderFactory.createLineBorder(color, 1));
        return l;
    }

    // =========================================================================
    // TAB 1: ONLINE USERS
    // =========================================================================

    private JPanel createOnlineUsersTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        String[] cols = {"Tên người dùng", "Server kết nối trực tiếp", "Loại kết nối", "Hành động"};
        modelOnlineUsers = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        tblOnlineUsers = new JTable(modelOnlineUsers);
        tblOnlineUsers.setRowHeight(28);
        tblOnlineUsers.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        tblOnlineUsers.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 13));

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        JButton btnKick = new JButton("🛑 Kick người dùng này");
        btnKick.setBackground(new Color(220, 53, 69));
        btnKick.setForeground(Color.WHITE);
        btnKick.setFocusPainted(false);

        JButton btnRefreshUsers = new JButton("🔄 Làm mới");
        btnRefreshUsers.setFocusPainted(false);

        btnKick.addActionListener(e -> {
            int selectedRow = tblOnlineUsers.getSelectedRow();
            if (selectedRow < 0) {
                JOptionPane.showMessageDialog(this, "Vui lòng chọn một người dùng từ bảng để kick!", "Chưa chọn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String username = (String) modelOnlineUsers.getValueAt(selectedRow, 0);
            String serverOrigin = (String) modelOnlineUsers.getValueAt(selectedRow, 1);

            int confirm = JOptionPane.showConfirmDialog(this,
                    "Bạn có chắc muốn kick người dùng '" + username + "' khỏi hệ thống?",
                    "Xác nhận Kick", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                if (serverOrigin.equals(server.getServerId())) {
                    server.kickUser(username);
                    JOptionPane.showMessageDialog(this, "Đã kick thành công " + username);
                } else {
                    JOptionPane.showMessageDialog(this,
                            "Người dùng này kết nối vào " + serverOrigin + "!\nHãy sử dụng Trang Quản Trị của " + serverOrigin + " để kick.",
                            "Không thể kick từ xa", JOptionPane.INFORMATION_MESSAGE);
                }
                refreshOnlineUsersTable();
            }
        });

        btnRefreshUsers.addActionListener(e -> refreshOnlineUsersTable());

        btnBar.add(btnKick);
        btnBar.add(btnRefreshUsers);

        p.add(new JScrollPane(tblOnlineUsers), BorderLayout.CENTER);
        p.add(btnBar, BorderLayout.SOUTH);
        return p;
    }

    // =========================================================================
    // TAB 2: AUDIT ACTIVITY LOGS
    // =========================================================================

    private JPanel createActivityLogsTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        String[] cols = {"ID", "Thời gian", "Người dùng", "Hành động", "Tại Server"};
        modelActivityLogs = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        tblActivityLogs = new JTable(modelActivityLogs);
        tblActivityLogs.setRowHeight(26);
        tblActivityLogs.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tblActivityLogs.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 13));

        // Tùy biến màu hành động
        tblActivityLogs.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (column == 3 && value != null) {
                    String action = value.toString();
                    if (action.contains("ĐĂNG NHẬP") || action.contains("ONLINE")) {
                        c.setForeground(new Color(40, 167, 69));
                    } else if (action.contains("KICK") || action.contains("MẤT KẾT NỐI")) {
                        c.setForeground(Color.RED);
                    } else {
                        c.setForeground(new Color(108, 117, 125));
                    }
                } else {
                    c.setForeground(Color.BLACK);
                }
                return c;
            }
        });

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        JButton btnReloadLogs = new JButton("🔄 Nạp lại từ SQLite");
        btnReloadLogs.addActionListener(e -> refreshActivityLogsTable());
        btnBar.add(btnReloadLogs);

        p.add(new JScrollPane(tblActivityLogs), BorderLayout.CENTER);
        p.add(btnBar, BorderLayout.SOUTH);
        return p;
    }

    // =========================================================================
    // TAB 3: SYNC MONITOR & CHỐNG LẶP VÔ HẠN
    // =========================================================================

    private JPanel createSyncMonitorTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        String[] cols = {"Thời gian", "Loại gói tin", "Server liên quan", "Mã tin nhắn (ID)", "Chi tiết / Kết quả"};
        modelSyncLogs = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        tblSyncLogs = new JTable(modelSyncLogs);
        tblSyncLogs.setRowHeight(26);
        tblSyncLogs.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tblSyncLogs.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 13));

        // Highlight màu sắc để giảng viên nhìn thấy rõ cơ chế chống lặp
        tblSyncLogs.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (column == 1 && value != null) {
                    String type = value.toString();
                    if (type.contains("CHỐNG LẶP")) {
                        c.setForeground(new Color(220, 53, 69)); // Đỏ cảnh báo chặn lặp
                        setFont(getFont().deriveFont(Font.BOLD));
                    } else if (type.contains("ĐỒNG BỘ ĐẾN")) {
                        c.setForeground(new Color(40, 167, 69)); // Xanh nhận sync
                    } else if (type.contains("ĐỒNG BỘ ĐI")) {
                        c.setForeground(new Color(0, 123, 255)); // Xanh dương gửi sync
                    }
                } else {
                    c.setForeground(Color.BLACK);
                }
                return c;
            }
        });

        JLabel lblNotice = new JLabel("<html>💡 <i>Tab này trực quan hóa thuật toán chống lặp vô hạn (Anti-Infinite Loop) và các gói tin RMI trao đổi giữa 2 Server.</i></html>");
        lblNotice.setBorder(new EmptyBorder(4, 4, 4, 4));

        p.add(lblNotice, BorderLayout.NORTH);
        p.add(new JScrollPane(tblSyncLogs), BorderLayout.CENTER);
        return p;
    }

    // =========================================================================
    // TAB 4: SQLITE DATABASE VIEWER
    // =========================================================================

    private JPanel createDatabaseTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));

        String[] cols = {"Mã UUID", "Thời gian", "Người gửi", "Người nhận", "Nội dung tin", "Server gốc", "Loại"};
        modelDbMessages = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        tblDbMessages = new JTable(modelDbMessages);
        tblDbMessages.setRowHeight(26);
        tblDbMessages.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tblDbMessages.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 13));

        JPanel topBar = new JPanel(new BorderLayout());
        JLabel lblDbPath = new JLabel("📁 File CSDL: " + dbManager.getDbUrl());
        lblDbPath.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        lblDbPath.setForeground(Color.DARK_GRAY);

        JButton btnReloadDb = new JButton("🔄 Làm mới CSDL");
        btnReloadDb.addActionListener(e -> refreshDatabaseTable());

        topBar.add(lblDbPath, BorderLayout.WEST);
        topBar.add(btnReloadDb, BorderLayout.EAST);

        p.add(topBar, BorderLayout.NORTH);
        p.add(new JScrollPane(tblDbMessages), BorderLayout.CENTER);
        return p;
    }

    // =========================================================================
    // BOTTOM BAR: ADMIN ACTIONS
    // =========================================================================

    private JPanel createBottomActionPanel() {
        JPanel p = new JPanel(new BorderLayout(8, 6));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 220), 1),
                new EmptyBorder(8, 10, 8, 10)
        ));

        JPanel leftPanel = new JPanel(new BorderLayout(6, 0));
        leftPanel.add(new JLabel("📢 Thông báo Admin:"), BorderLayout.WEST);
        txtAdminNotice = new JTextField();
        txtAdminNotice.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        leftPanel.add(txtAdminNotice, BorderLayout.CENTER);

        btnSendNotice = new JButton("Gửi Thông Báo Toàn Mạng");
        btnSendNotice.setBackground(new Color(255, 153, 0));
        btnSendNotice.setForeground(Color.WHITE);
        btnSendNotice.setFocusPainted(false);
        leftPanel.add(btnSendNotice, BorderLayout.EAST);

        btnForceSync = new JButton("🔄 Ép Buộc Đồng Bộ Dữ Liệu");
        btnForceSync.setBackground(new Color(0, 123, 255));
        btnForceSync.setForeground(Color.WHITE);
        btnForceSync.setFocusPainted(false);

        p.add(leftPanel, BorderLayout.CENTER);
        p.add(btnForceSync, BorderLayout.EAST);

        // Sự kiện gửi thông báo Admin
        btnSendNotice.addActionListener(e -> {
            String notice = txtAdminNotice.getText().trim();
            if (!notice.isEmpty()) {
                server.broadcastAdminAnnouncement(notice);
                txtAdminNotice.setText("");
                JOptionPane.showMessageDialog(this, "Đã phát thông báo thành công tới tất cả Client!", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        // Sự kiện ép đồng bộ
        btnForceSync.addActionListener(e -> {
            server.triggerForceSync();
            refreshAllData();
            JOptionPane.showMessageDialog(this, "Đã kích hoạt kéo toàn bộ dữ liệu từ Server đối tác!", "Đồng bộ", JOptionPane.INFORMATION_MESSAGE);
        });

        return p;
    }

    // =========================================================================
    // DỮ LIỆU & REFRESH LOGIC
    // =========================================================================

    public void refreshAllData() {
        refreshOnlineUsersTable();
        refreshActivityLogsTable();
        refreshDatabaseTable();
        updateStatCounters();
    }

    private void refreshOnlineUsersTable() {
        SwingUtilities.invokeLater(() -> {
            modelOnlineUsers.setRowCount(0);
            // Local users
            for (String u : server.getLocalClients().keySet()) {
                modelOnlineUsers.addRow(new Object[]{u, server.getServerId(), "Trực tiếp (Local)", "Có thể kick"});
            }
            // Remote users
            for (String u : server.getRemoteOnlineUsers()) {
                modelOnlineUsers.addRow(new Object[]{u, server.getPartnerServerId(), "Qua RMI Sync (Remote)", "Kick tại đối tác"});
            }
            updateStatCounters();
        });
    }

    private void refreshActivityLogsTable() {
        SwingUtilities.invokeLater(() -> {
            modelActivityLogs.setRowCount(0);
            List<DatabaseManager.ActivityLog> logs = dbManager.getAllActivityLogs();
            for (DatabaseManager.ActivityLog log : logs) {
                modelActivityLogs.addRow(new Object[]{
                        log.getId(),
                        log.getFormattedTime(),
                        log.getUsername(),
                        log.getAction(),
                        log.getServerId()
                });
            }
        });
    }

    private void refreshDatabaseTable() {
        SwingUtilities.invokeLater(() -> {
            modelDbMessages.setRowCount(0);
            List<ChatMessage> list = dbManager.getAllMessages();
            for (ChatMessage m : list) {
                String target = m.isPrivate() ? "🔒 @" + m.getRecipient() : "🌐 Phòng chung";
                String typeLabel = m.isPrivate() ? "RIÊNG TƯ" : m.getType().name();
                modelDbMessages.addRow(new Object[]{
                        m.getMessageId().substring(0, Math.min(8, m.getMessageId().length())) + "...",
                        m.getFormattedTime(),
                        m.getSender(),
                        target,
                        m.getContent(),
                        m.getOriginServer(),
                        typeLabel
                });
            }
            updateStatCounters();
        });
    }

    private void updateStatCounters() {
        int totalUsers = server.getLocalClients().size() + server.getRemoteOnlineUsers().size();
        int totalMessages = dbManager.getMessageCount();

        lblStatUsers.setText(String.format("<html><center><small>Online</small><br><b><font size='+1'>%d</font></b></center></html>", totalUsers));
        lblStatMessages.setText(String.format("<html><center><small>Tin nhắn</small><br><b><font size='+1'>%d</font></b></center></html>", totalMessages));
        lblStatSync.setText(String.format("<html><center><small>Đồng bộ</small><br><b><font size='+1'>%d</font></b></center></html>", syncPacketCount));
    }

    private void updatePartnerBadge(boolean connected, String partnerHost) {
        if (connected) {
            lblPartnerStatus.setText("🟢 ĐÃ KẾT NỐI VỚI " + server.getPartnerServerId().toUpperCase() + " (" + partnerHost + ")");
            lblPartnerStatus.setForeground(new Color(40, 167, 69));
        } else {
            lblPartnerStatus.setText("🔴 CHƯA KẾT NỐI ĐỐI TÁC (" + partnerHost + ")");
            lblPartnerStatus.setForeground(new Color(220, 53, 69));
        }
    }

    // =========================================================================
    // IMPLEMENTATION: ServerEventListener (NHẬN SỰ KIỆN REAL-TIME TỪ SERVER)
    // =========================================================================

    @Override
    public void onUserJoined(String username, String originServer, long timestamp) {
        refreshOnlineUsersTable();
        refreshActivityLogsTable();
    }

    @Override
    public void onUserLeft(String username, String originServer, String reason, long timestamp) {
        refreshOnlineUsersTable();
        refreshActivityLogsTable();
    }

    @Override
    public void onMessageEvent(ChatMessage message) {
        refreshDatabaseTable();
    }

    @Override
    public void onSyncSent(ChatMessage message, String targetServer) {
        syncPacketCount++;
        SwingUtilities.invokeLater(() -> {
            String prefix = message.isPrivate() ? "🔒 [RIÊNG TƯ -> @" + message.getRecipient() + "] " : "";
            modelSyncLogs.insertRow(0, new Object[]{
                    timeFormat.format(new Date()),
                    "📤 ĐỒNG BỘ ĐI",
                    targetServer,
                    message.getMessageId().substring(0, Math.min(8, message.getMessageId().length())),
                    String.format("%sGửi tin của [%s]: '%s'", prefix, message.getSender(), message.getContent())
            });
            updateStatCounters();
        });
    }

    @Override
    public void onSyncReceived(ChatMessage message, String fromServer) {
        syncPacketCount++;
        SwingUtilities.invokeLater(() -> {
            String prefix = message.isPrivate() ? "🔒 [RIÊNG TƯ TỚI @" + message.getRecipient() + "] " : "";
            modelSyncLogs.insertRow(0, new Object[]{
                    timeFormat.format(new Date()),
                    "📥 ĐỒNG BỘ ĐẾN",
                    fromServer,
                    message.getMessageId().substring(0, Math.min(8, message.getMessageId().length())),
                    String.format("%sNhận tin từ [%s]: '%s'", prefix, message.getSender(), message.getContent())
            });
            updateStatCounters();
        });
    }

    @Override
    public void onSyncDuplicateBlocked(String messageId, String originServer) {
        syncPacketCount++;
        SwingUtilities.invokeLater(() -> {
            modelSyncLogs.insertRow(0, new Object[]{
                    timeFormat.format(new Date()),
                    "⛔ CHỐNG LẶP VÔ HẠN",
                    originServer,
                    messageId.substring(0, Math.min(8, messageId.length())),
                    "Phát hiện tin nhắn đã xử lý trong cache -> BỎ QUA AN TOÀN"
            });
            updateStatCounters();
        });
    }

    @Override
    public void onPartnerStatusChanged(boolean connected, String partnerInfo) {
        SwingUtilities.invokeLater(() -> {
            updatePartnerBadge(connected, partnerInfo);
        });
    }
}

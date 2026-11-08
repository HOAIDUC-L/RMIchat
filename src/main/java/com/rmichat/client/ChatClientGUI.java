package com.rmichat.client;

import com.rmichat.common.ChatMessage;
import com.rmichat.common.Constants;
import com.rmichat.interfaces.ChatServerInterface;
import com.rmichat.server.ServerMain;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.InetAddress;
import java.rmi.Naming;
import java.util.List;

/**
 * Giao diện người dùng Java Swing hiện đại cho RMI Chat Client.
 * Hỗ trợ chọn nhanh kết nối Server 1 hoặc Server 2, đổi IP linh hoạt khi chạy trong LAN/máy ảo.
 */
public class ChatClientGUI extends JFrame implements ChatClientCallbackImpl.ClientCallbackListener {

    private String currentUsername;
    private ChatServerInterface serverStub;
    private ChatClientCallbackImpl callbackObj;

    // Components - Thanh điều khiển kết nối phía trên
    private JTextField txtUsername;
    private JComboBox<String> cbServerSelect;
    private JTextField txtServerIp;
    private JTextField txtServerPort;
    private JTextField txtClientIp; // IP quảng bá cho Callback
    private JButton btnConnect;
    private JButton btnDisconnect;
    private JLabel lblStatus;

    // Components - Vùng chat chính và Danh sách Online
    private JTextPane chatPane;
    private HTMLEditorKit htmlKit;
    private HTMLDocument htmlDoc;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JLabel lblUserCount;

    // Components - Vùng nhập liệu phía dưới
    private JComboBox<String> cbRecipient;
    private static final String BROADCAST_TARGET = "🌐 Tất cả mọi người (Phòng chung)";
    private JTextField txtMessageInput;
    private JButton btnSend;

    public ChatClientGUI() {
        super("RMI Chat Client - Đồ án Lập trình Mạng Lab 5");
        initUI();
    }

    private void initUI() {
        setSize(950, 650);
        setMinimumSize(new Dimension(800, 500));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // Xử lý sự kiện đóng cửa sổ -> Ngắt kết nối an toàn
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnectServer();
                System.exit(0);
            }
        });

        // Layout chính
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 1. TOP PANEL: Cấu hình kết nối
        mainPanel.add(createConnectionPanel(), BorderLayout.NORTH);

        // 2. CENTER PANEL: Nội dung Chat + Sidebar User
        mainPanel.add(createChatAndUserPanel(), BorderLayout.CENTER);

        // 3. BOTTOM PANEL: Khung nhập tin nhắn
        mainPanel.add(createInputPanel(), BorderLayout.SOUTH);

        setContentPane(mainPanel);

        // Cập nhật trạng thái ban đầu của các nút
        updateConnectionState(false);
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Cấu hình Kết nối Máy chủ & Mạng LAN"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Dòng 1: Chọn Server, IP Server, Port Server
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Máy chủ mục tiêu:"), gbc);

        gbc.gridx = 1;
        cbServerSelect = new JComboBox<>(new String[]{
                "Server 1 (Cổng mặc định: 1099)",
                "Server 2 (Cổng mặc định: 1100)",
                "Tùy chỉnh (Custom IP/Port)"
        });
        panel.add(cbServerSelect, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel("IP Server:"), gbc);

        gbc.gridx = 3;
        txtServerIp = new JTextField("127.0.0.1", 12);
        panel.add(txtServerIp, gbc);

        gbc.gridx = 4;
        panel.add(new JLabel("Cổng:"), gbc);

        gbc.gridx = 5;
        txtServerPort = new JTextField(String.valueOf(Constants.DEFAULT_SERVER_1_PORT), 5);
        panel.add(txtServerPort, gbc);

        // Dòng 2: Tên người dùng, IP máy này (dành cho RMI Callback khi chạy LAN), Nút kết nối
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Tên đăng nhập:"), gbc);

        gbc.gridx = 1;
        txtUsername = new JTextField("User_" + (System.currentTimeMillis() % 1000), 12);
        panel.add(txtUsername, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel("IP Máy này (Callback):"), gbc);

        gbc.gridx = 3;
        String detectedIp = ServerMain.getLocalLanIp();
        txtClientIp = new JTextField(detectedIp, 12);
        panel.add(txtClientIp, gbc);

        gbc.gridx = 4; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnConnect = new JButton("Kết nối");
        btnConnect.setBackground(new Color(40, 167, 69));
        btnConnect.setForeground(Color.WHITE);
        btnConnect.setFocusPainted(false);

        btnDisconnect = new JButton("Ngắt kết nối");
        btnDisconnect.setBackground(new Color(220, 53, 69));
        btnDisconnect.setForeground(Color.WHITE);
        btnDisconnect.setFocusPainted(false);
        btnDisconnect.setEnabled(false);

        btnPanel.add(btnConnect);
        btnPanel.add(btnDisconnect);
        panel.add(btnPanel, gbc);

        // Dòng 3: Trạng thái kết nối
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 6;
        lblStatus = new JLabel("Chưa kết nối. Vui lòng chọn Server và bấm 'Kết nối'.");
        lblStatus.setForeground(Color.DARK_GRAY);
        panel.add(lblStatus, gbc);

        // Sự kiện khi đổi Server trong dropdown
        cbServerSelect.addActionListener(e -> {
            int idx = cbServerSelect.getSelectedIndex();
            if (idx == 0) { // Server 1
                txtServerPort.setText(String.valueOf(Constants.DEFAULT_SERVER_1_PORT));
            } else if (idx == 1) { // Server 2
                txtServerPort.setText(String.valueOf(Constants.DEFAULT_SERVER_2_PORT));
            }
        });

        // Sự kiện nút kết nối
        btnConnect.addActionListener(e -> connectServer());
        btnDisconnect.addActionListener(e -> disconnectServer());

        return panel;
    }

    private JPanel createChatAndUserPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(8, 0));

        // Khung hiển thị chat bằng HTML
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setContentType("text/html");
        htmlKit = new HTMLEditorKit();
        htmlDoc = new HTMLDocument();
        chatPane.setEditorKit(htmlKit);
        chatPane.setDocument(htmlDoc);
        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(BorderFactory.createTitledBorder("Nội dung Cuộc trò chuyện"));

        // Sidebar Danh sách người dùng online
        JPanel userPanel = new JPanel(new BorderLayout(4, 4));
        userPanel.setPreferredSize(new Dimension(210, 0));
        userPanel.setBorder(BorderFactory.createTitledBorder("Người dùng Online"));

        lblUserCount = new JLabel("Online: 0", SwingConstants.CENTER);
        lblUserCount.setFont(lblUserCount.getFont().deriveFont(Font.BOLD));
        lblUserCount.setBorder(new EmptyBorder(4, 4, 4, 4));
        userPanel.add(lblUserCount, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String name = (String) value;
                if (name != null && name.equals(currentUsername)) {
                    l.setText("👤 " + name + " (Bạn)");
                    l.setForeground(new Color(0, 102, 204));
                    l.setFont(l.getFont().deriveFont(Font.BOLD));
                } else {
                    l.setText("🟢 " + name);
                    l.setForeground(Color.BLACK);
                }
                l.setBorder(new EmptyBorder(3, 5, 3, 5));
                return l;
            }
        });

        // Bấm vào tên người dùng ở danh sách để chọn nhắn riêng tức thì
        userList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                String selected = userList.getSelectedValue();
                if (selected != null && !selected.equals(currentUsername) && cbRecipient != null) {
                    for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                        if (cbRecipient.getItemAt(i).equals(selected)) {
                            cbRecipient.setSelectedIndex(i);
                            txtMessageInput.requestFocus();
                            break;
                        }
                    }
                }
            }
        });

        JScrollPane userScroll = new JScrollPane(userList);
        userPanel.add(userScroll, BorderLayout.CENTER);

        centerPanel.add(chatScroll, BorderLayout.CENTER);
        centerPanel.add(userPanel, BorderLayout.EAST);

        return centerPanel;
    }

    private JPanel createInputPanel() {
        JPanel container = new JPanel(new BorderLayout(4, 4));
        container.setBorder(new EmptyBorder(6, 2, 2, 2));

        // Thanh chọn người nhận (Nhắn riêng / Phòng chung)
        JPanel targetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        targetPanel.add(new JLabel("Gửi tới:"));
        cbRecipient = new JComboBox<>(new String[]{ BROADCAST_TARGET });
        cbRecipient.setPreferredSize(new Dimension(230, 26));
        cbRecipient.setEnabled(false);
        targetPanel.add(cbRecipient);

        JButton btnResetTarget = new JButton("🌐 Về phòng chung");
        btnResetTarget.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnResetTarget.setFocusPainted(false);
        btnResetTarget.addActionListener(e -> {
            if (cbRecipient != null && cbRecipient.getItemCount() > 0) {
                cbRecipient.setSelectedIndex(0);
                txtMessageInput.requestFocus();
            }
        });
        targetPanel.add(btnResetTarget);

        JLabel lblHint = new JLabel("(💡 Click đúp vào tên ở cột bên phải để chọn nhắn riêng)");
        lblHint.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        lblHint.setForeground(Color.GRAY);
        targetPanel.add(lblHint);

        // Khung nhập tin nhắn và nút gửi
        JPanel msgPanel = new JPanel(new BorderLayout(8, 0));
        txtMessageInput = new JTextField();
        txtMessageInput.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtMessageInput.setEnabled(false);

        btnSend = new JButton("Gửi (Enter)");
        btnSend.setBackground(new Color(0, 123, 255));
        btnSend.setForeground(Color.WHITE);
        btnSend.setFocusPainted(false);
        btnSend.setEnabled(false);

        msgPanel.add(txtMessageInput, BorderLayout.CENTER);
        msgPanel.add(btnSend, BorderLayout.EAST);

        container.add(targetPanel, BorderLayout.NORTH);
        container.add(msgPanel, BorderLayout.CENTER);

        // Sự kiện gửi tin
        txtMessageInput.addActionListener(e -> sendMessage());
        btnSend.addActionListener(e -> sendMessage());

        return container;
    }

    // =========================================================================
    // LOGIC KẾT NỐI VÀ GỬI NHẬN QUA RMI
    // =========================================================================

    private void connectServer() {
        String username = txtUsername.getText().trim();
        if (username.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên đăng nhập!", "Lỗi", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String serverIp = txtServerIp.getText().trim();
        String portStr = txtServerPort.getText().trim();
        String clientIp = txtClientIp.getText().trim();

        if (serverIp.isEmpty() || portStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập đầy đủ IP và Cổng của Server!", "Lỗi", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Cổng phải là số nguyên!", "Lỗi", JOptionPane.WARNING_MESSAGE);
            return;
        }

        btnConnect.setEnabled(false);
        lblStatus.setText("Đang kết nối tới Server...");

        // Chạy tiến trình kết nối trong background thread để không treo UI
        new Thread(() -> {
            try {
                // ĐẶC BIỆT QUAN TRỌNG: Thiết lập IP máy này cho RMI Callback
                // để Server trên máy thật có thể gọi ngược lại Client trên máy ảo hoặc máy khác trong LAN
                if (!clientIp.isEmpty()) {
                    System.setProperty("java.rmi.server.hostname", clientIp);
                }

                // 1. Tìm kiếm dịch vụ RMI của Server
                String serverUrl = String.format("rmi://%s:%d/%s", serverIp, port, Constants.CHAT_SERVICE_NAME);
                ChatServerInterface stub = (ChatServerInterface) Naming.lookup(serverUrl);

                // 2. Tạo đối tượng Callback để nhận dữ liệu Server đẩy về
                ChatClientCallbackImpl callback = new ChatClientCallbackImpl(this);

                // 3. Đăng ký với Server
                boolean success = stub.registerClient(username, callback);

                if (!success) {
                    SwingUtilities.invokeLater(() -> {
                        btnConnect.setEnabled(true);
                        lblStatus.setText("Đăng nhập thất bại: Tên người dùng đã tồn tại trên hệ thống!");
                        JOptionPane.showMessageDialog(this,
                                "Tên '" + username + "' đã được sử dụng ở một máy khác!\nVui lòng chọn tên đăng nhập khác.",
                                "Trùng tên đăng nhập", JOptionPane.ERROR_MESSAGE);
                    });
                    return;
                }

                this.serverStub = stub;
                this.callbackObj = callback;
                this.currentUsername = username;

                // 4. Lấy lịch sử chat và danh sách người dùng online
                List<ChatMessage> history = stub.getHistory();
                List<String> onlineUsers = stub.getOnlineUsers();

                boolean partnerOk = false;
                try {
                    partnerOk = stub.isPartnerServerConnected();
                } catch (Exception ignored) {}

                final boolean finalPartnerOk = partnerOk;
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(true);
                    String partnerBadge = finalPartnerOk ? "🟢 ĐÃ ĐỒNG BỘ ĐỐI TÁC" : "🔴 ĐỐI TÁC CHƯA BẬT (CHƯA ĐỒNG BỘ)";
                    lblStatus.setText(String.format("Kết nối: %s:%d [%s]  |  Đồng bộ 2 Server: %s", serverIp, port, username, partnerBadge));
                    lblStatus.setForeground(finalPartnerOk ? new Color(40, 167, 69) : new Color(210, 100, 0));

                    // Hiển thị lịch sử chat
                    clearChatPane();
                    appendSystemMessage("--- ĐÃ TẢI LỊCH SỬ TIN NHẮN ---");
                    for (ChatMessage msg : history) {
                        appendMessageToPane(msg);
                    }
                    appendSystemMessage("--- BẮT ĐẦU PHIÊN CHAT MỚI ---");

                    // Cập nhật danh sách online
                    updateUserList(onlineUsers);
                    txtMessageInput.requestFocus();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    btnConnect.setEnabled(true);
                    lblStatus.setText("Lỗi kết nối: " + e.getMessage());
                    lblStatus.setForeground(Color.RED);
                    JOptionPane.showMessageDialog(this,
                            "Không thể kết nối đến Server tại " + serverIp + ":" + port + "\nChi tiết: " + e.getMessage(),
                            "Lỗi kết nối RMI", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void disconnectServer() {
        if (serverStub != null && currentUsername != null) {
            new Thread(() -> {
                try {
                    serverStub.unregisterClient(currentUsername);
                } catch (Exception ignored) {}
            }).start();
        }

        serverStub = null;
        callbackObj = null;
        updateConnectionState(false);
        lblStatus.setText("Đã ngắt kết nối khỏi Server.");
        lblStatus.setForeground(Color.DARK_GRAY);
        userListModel.clear();
        lblUserCount.setText("Online: 0");
    }

    private void sendMessage() {
        String content = txtMessageInput.getText().trim();
        if (content.isEmpty() || serverStub == null) return;

        txtMessageInput.setText("");
        txtMessageInput.requestFocus();

        String selectedTarget = cbRecipient != null ? (String) cbRecipient.getSelectedItem() : null;
        ChatMessage msg;
        if (selectedTarget != null && !selectedTarget.equals(BROADCAST_TARGET)) {
            // Tin nhắn riêng (Private)
            msg = ChatMessage.createPrivate(currentUsername, selectedTarget, content, "");
        } else {
            // Tin nhắn phòng chung (Public)
            msg = ChatMessage.createChat(currentUsername, content, "");
        }

        new Thread(() -> {
            try {
                serverStub.sendMessage(msg);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    appendSystemMessage("[LỖI GỬI TIN]: " + e.getMessage());
                });
            }
        }).start();
    }

    private void updateConnectionState(boolean connected) {
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtUsername.setEnabled(!connected);
        cbServerSelect.setEnabled(!connected);
        txtServerIp.setEnabled(!connected);
        txtServerPort.setEnabled(!connected);
        txtClientIp.setEnabled(!connected);

        txtMessageInput.setEnabled(connected);
        btnSend.setEnabled(connected);
        if (cbRecipient != null) {
            cbRecipient.setEnabled(connected);
        }
    }

    // =========================================================================
    // CALLBACK LISTENER METHODS (ĐƯỢC GỌI TỰ ĐỘNG TỪ SERVER QUA RMI)
    // =========================================================================

    @Override
    public void onMessageReceived(ChatMessage message) {
        appendMessageToPane(message);
    }

    @Override
    public void onUserListChanged(List<String> users) {
        updateUserList(users);
    }

    @Override
    public void onNotification(String notice) {
        appendSystemMessage(notice);
        if (notice != null && serverStub != null) {
            if (notice.contains("liên kết") || notice.contains("kết nối")) {
                new Thread(() -> {
                    try {
                        boolean ok = serverStub.isPartnerServerConnected();
                        SwingUtilities.invokeLater(() -> {
                            String partnerBadge = ok ? "🟢 ĐÃ ĐỒNG BỘ ĐỐI TÁC" : "🔴 ĐỐI TÁC CHƯA BẬT (CHƯA ĐỒNG BỘ)";
                            lblStatus.setText(String.format("Kết nối: [%s]  |  Đồng bộ 2 Server: %s", currentUsername, partnerBadge));
                            lblStatus.setForeground(ok ? new Color(40, 167, 69) : new Color(210, 100, 0));
                        });
                    } catch (Exception ignored) {}
                }).start();
            }
        }
    }

    // =========================================================================
    // UI RENDERING HELPER (HIỂN THỊ HTML ĐẸP MẮT)
    // =========================================================================

    private void appendMessageToPane(ChatMessage msg) {
        try {
            StringBuilder html = new StringBuilder();
            String time = msg.getFormattedTime();
            String origin = msg.getOriginServer() != null ? msg.getOriginServer() : "Server";

            // Huy hiệu nguồn gốc Server (Server 1 màu xanh dương, Server 2 màu cam tím)
            String originBadge;
            if (origin.contains("1")) {
                originBadge = "<span style='background-color:#007bff; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[Server 1]</span>";
            } else if (origin.contains("2")) {
                originBadge = "<span style='background-color:#fd7e14; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[Server 2]</span>";
            } else {
                originBadge = "<span style='background-color:#6c757d; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[" + origin + "]</span>";
            }

            if (msg.getType() == ChatMessage.MessageType.JOIN) {
                html.append(String.format(
                        "<div style='margin:3px 0; font-family:sans-serif; color:#28a745; font-size:12px;'>" +
                                "<span style='color:#888;'>[%s]</span> %s <b>%s</b></div>",
                        time, originBadge, escapeHtml(msg.getContent())));
            } else if (msg.getType() == ChatMessage.MessageType.LEAVE) {
                html.append(String.format(
                        "<div style='margin:3px 0; font-family:sans-serif; color:#dc3545; font-size:12px;'>" +
                                "<span style='color:#888;'>[%s]</span> %s <i>%s</i></div>",
                        time, originBadge, escapeHtml(msg.getContent())));
            } else if (msg.getType() == ChatMessage.MessageType.SYSTEM) {
                html.append(String.format(
                        "<div style='margin:4px 0; font-family:sans-serif; color:#6c757d; font-size:12px;'>" +
                                "<span style='color:#888;'>[%s]</span> %s <b>[THÔNG BÁO]</b>: %s</div>",
                        time, originBadge, escapeHtml(msg.getContent())));
            } else if (msg.isPrivate()) {
                // Tin nhắn riêng tư (1-1 / Whisper)
                boolean isMe = msg.getSender().equals(currentUsername);
                String privateHeader;
                String bubbleColor;
                String borderStyle;
                String privateBadge;

                if (isMe) {
                    privateBadge = "<span style='background-color:#6f42c1; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[🔒 RIÊNG TƯ -> @" + escapeHtml(msg.getRecipient()) + "]</span>";
                    privateHeader = String.format("<span style='color:#6f42c1; font-weight:bold;'>%s (Bạn):</span>", escapeHtml(msg.getSender()));
                    bubbleColor = "#f8f0fc"; // Tím nhạt cho người gửi
                    borderStyle = "border-left: 3px solid #6f42c1;";
                } else {
                    privateBadge = "<span style='background-color:#d63384; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[🔒 RIÊNG TƯ TỪ @" + escapeHtml(msg.getSender()) + "]</span>";
                    privateHeader = String.format("<span style='color:#d63384; font-weight:bold;'>%s:</span>", escapeHtml(msg.getSender()));
                    bubbleColor = "#fff0f6"; // Hồng nhạt cho người nhận
                    borderStyle = "border-left: 3px solid #d63384;";
                }

                html.append(String.format(
                        "<div style='margin:5px 0; padding:6px 8px; background-color:%s; %s font-family:sans-serif; font-size:13px; border-radius:4px;'>" +
                                "<div style='margin-bottom:2px;'>" +
                                "<span style='color:#888; font-size:11px;'>[%s]</span> %s %s %s" +
                                "</div>" +
                                "<div style='color:#111; word-wrap:break-word; margin-left:5px;'>%s</div>" +
                                "</div>",
                        bubbleColor, borderStyle, time, originBadge, privateBadge, privateHeader, escapeHtml(msg.getContent())));
            } else {
                // Tin nhắn chat phòng chung thông thường
                boolean isMe = msg.getSender().equals(currentUsername);
                String senderStyle = isMe ? "color:#0056b3; font-weight:bold;" : "color:#212529; font-weight:bold;";
                String senderLabel = isMe ? msg.getSender() + " (Bạn)" : msg.getSender();
                String bubbleColor = isMe ? "#e8f4fd" : "#f8f9fa";
                String borderStyle = isMe ? "border-left: 3px solid #007bff;" : "border-left: 3px solid #6c757d;";

                html.append(String.format(
                        "<div style='margin:5px 0; padding:6px 8px; background-color:%s; %s font-family:sans-serif; font-size:13px; border-radius:4px;'>" +
                                "<div style='margin-bottom:2px;'>" +
                                "<span style='color:#888; font-size:11px;'>[%s]</span> %s <span style='%s'>%s:</span>" +
                                "</div>" +
                                "<div style='color:#111; word-wrap:break-word; margin-left:5px;'>%s</div>" +
                                "</div>",
                        bubbleColor, borderStyle, time, originBadge, senderStyle, escapeHtml(senderLabel), escapeHtml(msg.getContent())));
            }

            htmlKit.insertHTML(htmlDoc, htmlDoc.getLength(), html.toString(), 0, 0, null);
            chatPane.setCaretPosition(htmlDoc.getLength());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void appendSystemMessage(String notice) {
        try {
            String html = String.format(
                    "<div style='margin:4px 0; text-align:center; color:#6c757d; font-family:sans-serif; font-size:11px;'>" +
                            "<i>%s</i></div>", escapeHtml(notice));
            htmlKit.insertHTML(htmlDoc, htmlDoc.getLength(), html, 0, 0, null);
            chatPane.setCaretPosition(htmlDoc.getLength());
        } catch (Exception ignored) {}
    }

    private void clearChatPane() {
        try {
            htmlDoc = new HTMLDocument();
            chatPane.setDocument(htmlDoc);
        } catch (Exception ignored) {}
    }

    private void updateUserList(List<String> users) {
        userListModel.clear();
        String currentSelectedTarget = cbRecipient != null ? (String) cbRecipient.getSelectedItem() : null;

        if (cbRecipient != null) {
            cbRecipient.removeAllItems();
            cbRecipient.addItem(BROADCAST_TARGET);
        }

        if (users != null) {
            for (String u : users) {
                userListModel.addElement(u);
                if (cbRecipient != null && !u.equals(currentUsername)) {
                    cbRecipient.addItem(u);
                }
            }
            lblUserCount.setText("Online: " + users.size());
        } else {
            lblUserCount.setText("Online: 0");
        }

        // Khôi phục người nhận đã chọn nếu họ vẫn đang online
        if (cbRecipient != null && currentSelectedTarget != null) {
            for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                if (cbRecipient.getItemAt(i).equals(currentSelectedTarget)) {
                    cbRecipient.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

package com.rmichat.client;

import com.rmichat.common.ChatGroup;
import com.rmichat.common.ChatMessage;
import com.rmichat.common.Constants;
import com.rmichat.interfaces.ChatServerInterface;
import com.rmichat.server.ServerMain;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.rmi.Naming;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giao diện người dùng Java Swing hiện đại cho RMI Chat Client.
 * Hỗ trợ:
 *  - Chat chung, Chat riêng tư (1-1), Chat nhóm (Custom Group Chat).
 *  - Gửi đa phương tiện: Hình ảnh (kèm xem trước thumbnail), Tệp tin bất kỳ, Âm thanh / Ghi âm giọng nói.
 *  - Tạo nhóm mới, mời bạn bè xuyên server, tự động phát hiện mạng LAN.
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

    // Components - Vùng chat chính
    private JTextPane chatPane;
    private HTMLEditorKit htmlKit;
    private HTMLDocument htmlDoc;

    // Components - Sidebar Phải (TabbedPane: Online Users & Nhóm Chat)
    private JTabbedPane rightTabbedPane;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JLabel lblUserCount;

    private DefaultListModel<ChatGroup> groupListModel;
    private JList<ChatGroup> groupList;
    private JLabel lblGroupCount;
    private JButton btnCreateGroup;
    private JButton btnInviteGroup;
    private JButton btnLeaveGroup;

    // Components - Vùng nhập liệu & Gửi đa phương tiện phía dưới
    public static class TargetItem {
        public enum TargetType { ALL, USER, GROUP }
        public final TargetType type;
        public final String id;
        public final String displayName;

        public TargetItem(TargetType type, String id, String displayName) {
            this.type = type;
            this.id = id;
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TargetItem that = (TargetItem) o;
            return type == that.type && Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }
    }

    private JComboBox<TargetItem> cbRecipient;
    private static final TargetItem TARGET_ALL = new TargetItem(TargetItem.TargetType.ALL, null, "🌐 Tất cả mọi người (Phòng chung)");

    private JTextField txtMessageInput;
    private JButton btnSend;
    private JButton btnSendImage;
    private JButton btnSendFile;
    private JButton btnSendAudio;

    // Bộ nhớ đệm tin nhắn & Audio Player
    private final Map<String, ChatMessage> messageCache = new ConcurrentHashMap<>();
    private final List<String> cachedOnlineUsers = new ArrayList<>();
    private final List<ChatGroup> cachedGroups = new ArrayList<>();
    private Clip activeAudioClip = null;
    private final File tempCacheDir;

    public ChatClientGUI() {
        super("RMI Chat Multi-Server Client (Ảnh, File, Audio & Nhóm Chat)");
        File tmp = new File(System.getProperty("java.io.tmpdir"), "rmichat_media_cache");
        if (!tmp.exists()) tmp.mkdirs();
        this.tempCacheDir = tmp;
        initUI();
    }

    private void initUI() {
        setSize(1020, 720);
        setMinimumSize(new Dimension(850, 550));
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

        // 2. CENTER PANEL: Nội dung Chat + Sidebar Phải
        mainPanel.add(createChatAndSidebarPanel(), BorderLayout.CENTER);

        // 3. BOTTOM PANEL: Khung nhập tin nhắn & Nút Media
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
        panel.add(new JLabel("Máy chủ:"), gbc);

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
        txtServerPort = new JTextField(String.valueOf(Constants.DEFAULT_SERVER_1_PORT), 6);
        panel.add(txtServerPort, gbc);

        // Dòng 2: Tên người dùng, IP Callback máy này, Nút kết nối
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Tên bạn:"), gbc);

        gbc.gridx = 1;
        txtUsername = new JTextField("User_" + (new Random().nextInt(900) + 100), 12);
        panel.add(txtUsername, gbc);

        gbc.gridx = 2;
        panel.add(new JLabel("IP Máy này (Callback):"), gbc);

        gbc.gridx = 3;
        String detectedIp = ServerMain.getLocalLanIp();
        txtClientIp = new JTextField(detectedIp, 12);
        panel.add(txtClientIp, gbc);

        gbc.gridx = 4; gbc.gridwidth = 2;
        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btnConnect = new JButton("Kết nối");
        btnConnect.setBackground(new Color(40, 167, 69));
        btnConnect.setForeground(Color.WHITE);
        btnConnect.setFocusPainted(false);

        btnDisconnect = new JButton("Ngắt kết nối");
        btnDisconnect.setBackground(new Color(220, 53, 69));
        btnDisconnect.setForeground(Color.WHITE);
        btnDisconnect.setFocusPainted(false);
        btnDisconnect.setEnabled(false);

        btnGroup.add(btnConnect);
        btnGroup.add(btnDisconnect);
        panel.add(btnGroup, gbc);

        // Dòng 3: Dòng hiển thị trạng thái kết nối & đồng bộ 2 Server
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 6;
        lblStatus = new JLabel("Chưa kết nối. Nhấn 'Kết nối' để tham gia phòng chat.");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblStatus.setForeground(Color.DARK_GRAY);
        panel.add(lblStatus, gbc);

        // Sự kiện đổi combo box Server
        cbServerSelect.addActionListener(e -> {
            int idx = cbServerSelect.getSelectedIndex();
            if (idx == 0) {
                txtServerPort.setText(String.valueOf(Constants.DEFAULT_SERVER_1_PORT));
                txtServerPort.setEnabled(false);
            } else if (idx == 1) {
                txtServerPort.setText(String.valueOf(Constants.DEFAULT_SERVER_2_PORT));
                txtServerPort.setEnabled(false);
            } else {
                txtServerPort.setEnabled(true);
            }
        });
        cbServerSelect.setSelectedIndex(0);

        btnConnect.addActionListener(e -> connectServer());
        btnDisconnect.addActionListener(e -> disconnectServer());

        return panel;
    }

    private JPanel createChatAndSidebarPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));

        // Khung hiển thị nội dung chat (JTextPane render HTML)
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        htmlKit = new HTMLEditorKit();
        htmlDoc = (HTMLDocument) htmlKit.createDefaultDocument();
        chatPane.setEditorKit(htmlKit);
        chatPane.setDocument(htmlDoc);

        // Bắt sự kiện click vào link trong khung chat (xem ảnh phóng to, tải file, phát audio)
        chatPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                handleHyperlinkClick(e.getDescription());
            }
        });

        JScrollPane chatScroll = new JScrollPane(chatPane);
        chatScroll.setBorder(BorderFactory.createTitledBorder("Nội dung Cuộc trò chuyện & Tệp đính kèm"));

        // Sidebar bên phải dạng JTabbedPane: Tab 1 "Online", Tab 2 "Nhóm Chat"
        rightTabbedPane = new JTabbedPane();
        rightTabbedPane.setPreferredSize(new Dimension(250, 0));

        // TAB 1: NGƯỜI DÙNG ONLINE
        JPanel userPanel = new JPanel(new BorderLayout(4, 4));
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

        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = userList.getSelectedValue();
                    if (selected != null && !selected.equals(currentUsername) && cbRecipient != null) {
                        for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                            TargetItem item = cbRecipient.getItemAt(i);
                            if (item.type == TargetItem.TargetType.USER && item.id.equals(selected)) {
                                cbRecipient.setSelectedIndex(i);
                                txtMessageInput.requestFocus();
                                break;
                            }
                        }
                    }
                }
            }
        });
        userPanel.add(new JScrollPane(userList), BorderLayout.CENTER);
        rightTabbedPane.addTab("👤 Online", userPanel);

        // TAB 2: NHÓM CHAT (CUSTOM GROUP CHAT)
        JPanel groupPanel = new JPanel(new BorderLayout(4, 4));
        lblGroupCount = new JLabel("Nhóm của bạn: 0", SwingConstants.CENTER);
        lblGroupCount.setFont(lblGroupCount.getFont().deriveFont(Font.BOLD));
        lblGroupCount.setBorder(new EmptyBorder(4, 4, 4, 4));
        groupPanel.add(lblGroupCount, BorderLayout.NORTH);

        groupListModel = new DefaultListModel<>();
        groupList = new JList<>(groupListModel);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ChatGroup) {
                    ChatGroup g = (ChatGroup) value;
                    l.setText(String.format("👥 %s (%d TV)", g.getGroupName(), g.getMemberCount()));
                    l.setFont(l.getFont().deriveFont(Font.BOLD));
                    l.setForeground(new Color(25, 135, 84));
                }
                l.setBorder(new EmptyBorder(4, 6, 4, 6));
                return l;
            }
        });

        groupList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ChatGroup selGroup = groupList.getSelectedValue();
                    if (selGroup != null && cbRecipient != null) {
                        for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                            TargetItem item = cbRecipient.getItemAt(i);
                            if (item.type == TargetItem.TargetType.GROUP && item.id.equals(selGroup.getGroupId())) {
                                cbRecipient.setSelectedIndex(i);
                                txtMessageInput.requestFocus();
                                break;
                            }
                        }
                    }
                }
            }
        });
        groupPanel.add(new JScrollPane(groupList), BorderLayout.CENTER);

        // Các nút thao tác nhóm
        JPanel groupBtnPanel = new JPanel(new GridLayout(3, 1, 3, 3));
        btnCreateGroup = new JButton("➕ Tạo Nhóm Mới...");
        btnCreateGroup.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnCreateGroup.addActionListener(e -> showCreateGroupDialog());

        btnInviteGroup = new JButton("➕ Mời Vào Nhóm...");
        btnInviteGroup.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnInviteGroup.addActionListener(e -> showInviteGroupDialog());

        btnLeaveGroup = new JButton("🚪 Rời Nhóm Này");
        btnLeaveGroup.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btnLeaveGroup.addActionListener(e -> leaveSelectedGroup());

        groupBtnPanel.add(btnCreateGroup);
        groupBtnPanel.add(btnInviteGroup);
        groupBtnPanel.add(btnLeaveGroup);
        groupPanel.add(groupBtnPanel, BorderLayout.SOUTH);

        rightTabbedPane.addTab("👥 Nhóm Chat", groupPanel);

        centerPanel.add(chatScroll, BorderLayout.CENTER);
        centerPanel.add(rightTabbedPane, BorderLayout.EAST);

        return centerPanel;
    }

    private JPanel createInputPanel() {
        JPanel container = new JPanel(new BorderLayout(4, 4));
        container.setBorder(new EmptyBorder(6, 2, 2, 2));

        // 1. Thanh chọn nơi nhận tin nhắn (Phòng chung / Người dùng / Nhóm)
        JPanel targetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        targetPanel.add(new JLabel("Gửi tới:"));
        cbRecipient = new JComboBox<>();
        cbRecipient.addItem(TARGET_ALL);
        cbRecipient.setPreferredSize(new Dimension(280, 26));
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

        JLabel lblHint = new JLabel("(💡 Click đúp vào tên người dùng hoặc nhóm để chọn)");
        lblHint.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        lblHint.setForeground(Color.GRAY);
        targetPanel.add(lblHint);

        // 2. Thanh hành động Media & Soạn tin nhắn
        JPanel inputActionPanel = new JPanel(new BorderLayout(6, 0));

        // Các nút Đa phương tiện (Ảnh, Tệp, Ghi âm)
        JPanel mediaBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnSendImage = new JButton("🖼️ Gửi Ảnh");
        btnSendImage.setBackground(new Color(230, 242, 255));
        btnSendImage.setToolTipText("Gửi hình ảnh (PNG, JPG, GIF...) xem trước trực tiếp");
        btnSendImage.addActionListener(e -> pickAndSendImage());

        btnSendFile = new JButton("📎 Gửi Tệp");
        btnSendFile.setBackground(new Color(240, 240, 240));
        btnSendFile.setToolTipText("Gửi tài liệu hoặc tệp tin bất kỳ (tối đa 10MB)");
        btnSendFile.addActionListener(e -> pickAndSendFile());

        btnSendAudio = new JButton("🎙️ Thoại / Audio");
        btnSendAudio.setBackground(new Color(255, 238, 240));
        btnSendAudio.setToolTipText("Thu âm giọng nói trực tiếp qua Micro hoặc gửi file nhạc");
        btnSendAudio.addActionListener(e -> openAudioDialog());

        mediaBtns.add(btnSendImage);
        mediaBtns.add(btnSendFile);
        mediaBtns.add(btnSendAudio);

        // Khung nhập text và nút Gửi
        JPanel textAndSend = new JPanel(new BorderLayout(6, 0));
        txtMessageInput = new JTextField();
        txtMessageInput.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtMessageInput.setEnabled(false);

        btnSend = new JButton("Gửi (Enter)");
        btnSend.setBackground(new Color(0, 123, 255));
        btnSend.setForeground(Color.WHITE);
        btnSend.setFocusPainted(false);
        btnSend.setEnabled(false);

        textAndSend.add(txtMessageInput, BorderLayout.CENTER);
        textAndSend.add(btnSend, BorderLayout.EAST);

        inputActionPanel.add(mediaBtns, BorderLayout.WEST);
        inputActionPanel.add(textAndSend, BorderLayout.CENTER);

        container.add(targetPanel, BorderLayout.NORTH);
        container.add(inputActionPanel, BorderLayout.CENTER);

        // Sự kiện gửi tin bằng phím Enter hoặc nút Gửi
        txtMessageInput.addActionListener(e -> sendTextMessage());
        btnSend.addActionListener(e -> sendTextMessage());

        return container;
    }

    // =========================================================================
    // LOGIC KẾT NỐI VÀ GỬI NHẬN QUA RMI
    // =========================================================================

    private void connectServer() {
        String username = txtUsername.getText().trim();
        if (username.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên người dùng!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String serverIp = txtServerIp.getText().trim();
        int port;
        try {
            port = Integer.parseInt(txtServerPort.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Cổng máy chủ không hợp lệ!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String clientIp = txtClientIp.getText().trim();
        if (!clientIp.isEmpty()) {
            System.setProperty("java.rmi.server.hostname", clientIp);
        }

        btnConnect.setEnabled(false);
        lblStatus.setText("Đang kết nối tới " + serverIp + ":" + port + "...");
        lblStatus.setForeground(new Color(0, 102, 204));

        new Thread(() -> {
            try {
                String rmiUrl = String.format("rmi://%s:%d/%s", serverIp, port, Constants.CHAT_SERVICE_NAME);
                ChatServerInterface stub = (ChatServerInterface) Naming.lookup(rmiUrl);

                ChatClientCallbackImpl callback = new ChatClientCallbackImpl(this);

                boolean registered = stub.registerClient(username, callback);
                if (!registered) {
                    SwingUtilities.invokeLater(() -> {
                        btnConnect.setEnabled(true);
                        lblStatus.setText("Tên '" + username + "' đã được sử dụng. Vui lòng chọn tên khác!");
                        lblStatus.setForeground(Color.RED);
                        JOptionPane.showMessageDialog(this,
                                "Tên '" + username + "' đã có người sử dụng trên hệ thống.\nVui lòng đổi tên khác!",
                                "Trùng tên", JOptionPane.WARNING_MESSAGE);
                    });
                    return;
                }

                serverStub = stub;
                callbackObj = callback;
                currentUsername = username;

                boolean partnerOk = false;
                try {
                    partnerOk = stub.isPartnerServerConnected();
                } catch (Exception ignored) {}

                List<ChatMessage> history = stub.getHistory();
                List<String> onlineUsers = stub.getOnlineUsers();
                List<ChatGroup> userGroups = stub.getGroupsForUser(username);

                final boolean finalPartnerOk = partnerOk;
                SwingUtilities.invokeLater(() -> {
                    updateConnectionState(true);
                    String partnerBadge = finalPartnerOk ? "🟢 ĐÃ ĐỒNG BỘ ĐỐI TÁC" : "🔴 ĐỐI TÁC CHƯA BẬT (CHƯA ĐỒNG BỘ)";
                    lblStatus.setText(String.format("Kết nối: %s:%d [%s]  |  Đồng bộ 2 Server: %s", serverIp, port, username, partnerBadge));
                    lblStatus.setForeground(finalPartnerOk ? new Color(40, 167, 69) : new Color(210, 100, 0));

                    // Hiển thị lịch sử chat
                    clearChatPane();
                    appendSystemMessage("--- ĐÃ TẢI LỊCH SỬ TIN NHẮN TỪ CSDL ---");
                    for (ChatMessage msg : history) {
                        appendMessageToPane(msg);
                    }
                    appendSystemMessage("--- BẮT ĐẦU PHIÊN CHAT MỚI ---");

                    // Cập nhật danh sách online & nhóm chat
                    updateUserList(onlineUsers);
                    updateGroupList(userGroups);
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
        stopAudioPlayback();
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
        groupListModel.clear();
        lblUserCount.setText("Online: 0");
        lblGroupCount.setText("Nhóm của bạn: 0");
    }

    private void sendTextMessage() {
        String content = txtMessageInput.getText().trim();
        if (content.isEmpty() || serverStub == null) return;

        txtMessageInput.setText("");
        txtMessageInput.requestFocus();

        TargetItem target = (TargetItem) cbRecipient.getSelectedItem();
        ChatMessage msg;

        if (target != null && target.type == TargetItem.TargetType.GROUP) {
            msg = ChatMessage.createGroupChat(currentUsername, target.id, target.displayName, content, "");
        } else if (target != null && target.type == TargetItem.TargetType.USER) {
            msg = ChatMessage.createPrivate(currentUsername, target.id, content, "");
        } else {
            msg = ChatMessage.createChat(currentUsername, content, "");
        }

        dispatchMessageToServer(msg);
    }

    private void pickAndSendImage() {
        if (serverStub == null) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Chọn hình ảnh để gửi");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Hình ảnh (*.png, *.jpg, *.jpeg, *.gif, *.bmp, *.webp)",
                "png", "jpg", "jpeg", "gif", "bmp", "webp"));

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            if (selectedFile.length() > 10 * 1024 * 1024) {
                JOptionPane.showMessageDialog(this, "Kích thước ảnh vượt quá giới hạn 10MB!", "Tệp quá lớn", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String caption = JOptionPane.showInputDialog(this, "Nhập chú thích cho bức ảnh (tùy chọn):", selectedFile.getName());
            if (caption == null) return; // Người dùng ấn Cancel

            try {
                byte[] data = Files.readAllBytes(selectedFile.toPath());
                TargetItem target = (TargetItem) cbRecipient.getSelectedItem();
                String rec = (target != null && target.type == TargetItem.TargetType.USER) ? target.id : null;
                String gid = (target != null && target.type == TargetItem.TargetType.GROUP) ? target.id : null;
                String gname = (target != null && target.type == TargetItem.TargetType.GROUP) ? target.displayName : null;

                ChatMessage imgMsg = ChatMessage.createImage(currentUsername, rec, gid, gname, selectedFile.getName(), data, caption, "");
                dispatchMessageToServer(imgMsg);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Lỗi đọc file ảnh: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void pickAndSendFile() {
        if (serverStub == null) return;
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Chọn tệp tin để gửi");

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            if (selectedFile.length() > 10 * 1024 * 1024) {
                JOptionPane.showMessageDialog(this, "Kích thước tệp tin vượt quá giới hạn 10MB!", "Tệp quá lớn", JOptionPane.WARNING_MESSAGE);
                return;
            }

            String caption = JOptionPane.showInputDialog(this, "Nhập mô tả tệp tin (tùy chọn):", selectedFile.getName());
            if (caption == null) return;

            try {
                byte[] data = Files.readAllBytes(selectedFile.toPath());
                TargetItem target = (TargetItem) cbRecipient.getSelectedItem();
                String rec = (target != null && target.type == TargetItem.TargetType.USER) ? target.id : null;
                String gid = (target != null && target.type == TargetItem.TargetType.GROUP) ? target.id : null;
                String gname = (target != null && target.type == TargetItem.TargetType.GROUP) ? target.displayName : null;

                ChatMessage fileMsg = ChatMessage.createFile(currentUsername, rec, gid, gname, selectedFile.getName(), data, caption, "");
                dispatchMessageToServer(fileMsg);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Lỗi đọc tệp tin: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void openAudioDialog() {
        if (serverStub == null) return;
        AudioRecorderDialog dialog = new AudioRecorderDialog(this, (audioData, fileName, caption) -> {
            TargetItem target = (TargetItem) cbRecipient.getSelectedItem();
            String rec = (target != null && target.type == TargetItem.TargetType.USER) ? target.id : null;
            String gid = (target != null && target.type == TargetItem.TargetType.GROUP) ? target.id : null;
            String gname = (target != null && target.type == TargetItem.TargetType.GROUP) ? target.displayName : null;

            ChatMessage audioMsg = ChatMessage.createAudio(currentUsername, rec, gid, gname, fileName, audioData, caption, "");
            dispatchMessageToServer(audioMsg);
        });
        dialog.setVisible(true);
    }

    private void dispatchMessageToServer(ChatMessage msg) {
        new Thread(() -> {
            try {
                serverStub.sendMessage(msg);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> appendSystemMessage("[LỖI GỬI TIN]: " + e.getMessage()));
            }
        }).start();
    }

    // =========================================================================
    // XỬ LÝ NHÓM CHAT (TẠO NHÓM, MỜI THÀNH VIÊN, RỜI NHÓM)
    // =========================================================================

    private void showCreateGroupDialog() {
        if (serverStub == null) return;

        JTextField txtGroupName = new JTextField(15);
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JLabel("Tên nhóm chat mới:"), BorderLayout.NORTH);
        panel.add(txtGroupName, BorderLayout.CENTER);

        // Danh sách người dùng online để tick chọn mời luôn
        JPanel userCheckPanel = new JPanel();
        userCheckPanel.setLayout(new BoxLayout(userCheckPanel, BoxLayout.Y_AXIS));
        List<JCheckBox> checkBoxes = new ArrayList<>();

        for (String u : cachedOnlineUsers) {
            if (!u.equals(currentUsername)) {
                JCheckBox cb = new JCheckBox(u);
                checkBoxes.add(cb);
                userCheckPanel.add(cb);
            }
        }

        if (!checkBoxes.isEmpty()) {
            JScrollPane sp = new JScrollPane(userCheckPanel);
            sp.setPreferredSize(new Dimension(200, 100));
            panel.add(new JLabel("Chọn thành viên ban đầu:"), BorderLayout.SOUTH);
            panel.add(sp, BorderLayout.SOUTH);
        }

        int result = JOptionPane.showConfirmDialog(this, panel, "➕ Tạo Nhóm Chat Mới",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String name = txtGroupName.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Tên nhóm không được để trống!", "Lỗi", JOptionPane.WARNING_MESSAGE);
                return;
            }

            List<String> chosenMembers = new ArrayList<>();
            for (JCheckBox cb : checkBoxes) {
                if (cb.isSelected()) {
                    chosenMembers.add(cb.getText());
                }
            }

            new Thread(() -> {
                try {
                    ChatGroup g = serverStub.createGroup(name, currentUsername, chosenMembers);
                    if (g != null) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this, "Tạo nhóm '" + g.getGroupName() + "' thành công!");
                        });
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Lỗi tạo nhóm: " + ex.getMessage()));
                }
            }).start();
        }
    }

    private void showInviteGroupDialog() {
        ChatGroup selGroup = groupList.getSelectedValue();
        if (selGroup == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một nhóm từ danh sách trước khi mời!", "Chưa chọn nhóm", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<String> candidates = new ArrayList<>();
        for (String u : cachedOnlineUsers) {
            if (!selGroup.isMember(u)) {
                candidates.add(u);
            }
        }

        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Hiện không có người dùng trực tuyến nào khác chưa ở trong nhóm.", "Thông báo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String targetUser = (String) JOptionPane.showInputDialog(this,
                "Chọn người dùng để mời vào nhóm '" + selGroup.getGroupName() + "':",
                "Mời Thành Viên", JOptionPane.QUESTION_MESSAGE, null,
                candidates.toArray(), candidates.get(0));

        if (targetUser != null && !targetUser.isEmpty()) {
            new Thread(() -> {
                try {
                    boolean ok = serverStub.inviteToGroup(selGroup.getGroupId(), currentUsername, targetUser);
                    SwingUtilities.invokeLater(() -> {
                        if (ok) {
                            JOptionPane.showMessageDialog(this, "Đã mời " + targetUser + " vào nhóm thành công!");
                        } else {
                            JOptionPane.showMessageDialog(this, "Mời thất bại hoặc người dùng đã ở trong nhóm.");
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Lỗi mời nhóm: " + ex.getMessage()));
                }
            }).start();
        }
    }

    private void leaveSelectedGroup() {
        ChatGroup selGroup = groupList.getSelectedValue();
        if (selGroup == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một nhóm từ danh sách để rời!", "Chưa chọn nhóm", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn rời khỏi nhóm '" + selGroup.getGroupName() + "'?",
                "Xác nhận rời nhóm", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            new Thread(() -> {
                try {
                    boolean ok = serverStub.leaveGroup(selGroup.getGroupId(), currentUsername);
                    SwingUtilities.invokeLater(() -> {
                        if (ok) {
                            JOptionPane.showMessageDialog(this, "Bạn đã rời khỏi nhóm '" + selGroup.getGroupName() + "'.");
                        }
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Lỗi rời nhóm: " + ex.getMessage()));
                }
            }).start();
        }
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
        btnSendImage.setEnabled(connected);
        btnSendFile.setEnabled(connected);
        btnSendAudio.setEnabled(connected);
        if (cbRecipient != null) {
            cbRecipient.setEnabled(connected);
        }
        btnCreateGroup.setEnabled(connected);
        btnInviteGroup.setEnabled(connected);
        btnLeaveGroup.setEnabled(connected);
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
    public void onGroupListChanged(List<ChatGroup> groups) {
        updateGroupList(groups);
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

    private void updateUserList(List<String> users) {
        cachedOnlineUsers.clear();
        if (users != null) cachedOnlineUsers.addAll(users);

        userListModel.clear();
        for (String user : cachedOnlineUsers) {
            userListModel.addElement(user);
        }
        lblUserCount.setText("Online: " + cachedOnlineUsers.size());
        refreshRecipientComboBox();
    }

    private void updateGroupList(List<ChatGroup> groups) {
        cachedGroups.clear();
        if (groups != null) cachedGroups.addAll(groups);

        groupListModel.clear();
        for (ChatGroup g : cachedGroups) {
            groupListModel.addElement(g);
        }
        lblGroupCount.setText("Nhóm của bạn: " + cachedGroups.size());
        refreshRecipientComboBox();
    }

    private void refreshRecipientComboBox() {
        TargetItem currentSel = (TargetItem) cbRecipient.getSelectedItem();

        cbRecipient.removeAllItems();
        cbRecipient.addItem(TARGET_ALL);

        // Thêm các user online
        for (String user : cachedOnlineUsers) {
            if (!user.equals(currentUsername)) {
                cbRecipient.addItem(new TargetItem(TargetItem.TargetType.USER, user, "👤 [Riêng tư] @" + user));
            }
        }

        // Thêm các nhóm chat
        for (ChatGroup g : cachedGroups) {
            cbRecipient.addItem(new TargetItem(TargetItem.TargetType.GROUP, g.getGroupId(), "👥 [Nhóm] " + g.getGroupName()));
        }

        if (currentSel != null) {
            for (int i = 0; i < cbRecipient.getItemCount(); i++) {
                if (cbRecipient.getItemAt(i).equals(currentSel)) {
                    cbRecipient.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    // =========================================================================
    // UI RENDERING HELPER (HIỂN THỊ HTML ĐẸP MẮT & XỬ LÝ MEDIA)
    // =========================================================================

    private void appendMessageToPane(ChatMessage msg) {
        try {
            messageCache.put(msg.getMessageId(), msg);

            StringBuilder html = new StringBuilder();
            String time = msg.getFormattedTime();
            String origin = msg.getOriginServer() != null ? msg.getOriginServer() : "Server";

            // Huy hiệu nguồn gốc Server
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
            } else {
                // Xác định ngữ cảnh: Nhóm, Riêng tư, hay Phòng chung
                boolean isMe = msg.getSender().equals(currentUsername);
                String badge;
                String bubbleColor;
                String borderStyle;
                String senderHeader;

                if (msg.isGroup()) {
                    // TIN NHẮN NHÓM
                    badge = "<span style='background-color:#20c997; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[👥 Nhóm: " +
                            escapeHtml(msg.getGroupName() != null ? msg.getGroupName() : "Nhóm") + "]</span>";
                    bubbleColor = isMe ? "#e8f8f5" : "#f0fdf9";
                    borderStyle = "border-left: 3px solid #20c997;";
                    senderHeader = isMe ? "<span style='color:#0f5132; font-weight:bold;'>Bạn:</span>" :
                            "<span style='color:#0f5132; font-weight:bold;'>" + escapeHtml(msg.getSender()) + ":</span>";

                } else if (msg.isPrivate()) {
                    // TIN NHẮN RIÊNG TƯ
                    if (isMe) {
                        badge = "<span style='background-color:#6f42c1; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[🔒 RIÊNG TƯ -> @" + escapeHtml(msg.getRecipient()) + "]</span>";
                        bubbleColor = "#f8f0fc";
                        borderStyle = "border-left: 3px solid #6f42c1;";
                        senderHeader = "<span style='color:#6f42c1; font-weight:bold;'>" + escapeHtml(msg.getSender()) + " (Bạn):</span>";
                    } else {
                        badge = "<span style='background-color:#d63384; color:white; font-size:10px; padding:2px 5px; border-radius:3px;'>[🔒 RIÊNG TƯ TỪ @" + escapeHtml(msg.getSender()) + "]</span>";
                        bubbleColor = "#fff0f6";
                        borderStyle = "border-left: 3px solid #d63384;";
                        senderHeader = "<span style='color:#d63384; font-weight:bold;'>" + escapeHtml(msg.getSender()) + ":</span>";
                    }
                } else {
                    // PHÒNG CHUNG
                    badge = "";
                    bubbleColor = isMe ? "#e8f4fd" : "#f8f9fa";
                    borderStyle = isMe ? "border-left: 3px solid #007bff;" : "border-left: 3px solid #6c757d;";
                    senderHeader = isMe ? "<span style='color:#0056b3; font-weight:bold;'>Bạn:</span>" :
                            "<span style='color:#212529; font-weight:bold;'>" + escapeHtml(msg.getSender()) + ":</span>";
                }

                // Render phần nội dung (Text hoặc Media)
                String bodyHtml = renderMessageBody(msg);

                html.append(String.format(
                        "<div style='margin:5px 0; padding:6px 8px; background-color:%s; %s font-family:sans-serif; font-size:13px; border-radius:4px;'>" +
                                "<div style='margin-bottom:3px;'>" +
                                "<span style='color:#888; font-size:11px;'>[%s]</span> %s %s %s" +
                                "</div>" +
                                "<div style='color:#111; word-wrap:break-word; margin-left:5px;'>%s</div>" +
                                "</div>",
                        bubbleColor, borderStyle, time, originBadge, badge, senderHeader, bodyHtml));
            }

            htmlKit.insertHTML(htmlDoc, htmlDoc.getLength(), html.toString(), 0, 0, null);
            chatPane.setCaretPosition(htmlDoc.getLength());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String renderMessageBody(ChatMessage msg) {
        StringBuilder sb = new StringBuilder();

        if (msg.isImage() && msg.getFileData() != null) {
            // Lưu ảnh tạm thời ra đĩa để thẻ <img src='file://...'> trong HTML của Swing load được
            File cachedImgFile = new File(tempCacheDir, msg.getMessageId() + "_" + msg.getFileName());
            if (!cachedImgFile.exists()) {
                try {
                    Files.write(cachedImgFile.toPath(), msg.getFileData());
                } catch (IOException ignored) {}
            }

            sb.append("<div style='margin:4px 0;'>");
            sb.append("<a href='preview:").append(msg.getMessageId()).append("'>");
            sb.append("<img src='file://").append(cachedImgFile.getAbsolutePath())
                    .append("' width='180' style='border:1px solid #ced4da; border-radius:4px;'>");
            sb.append("</a><br>");
            sb.append("<span style='font-size:11px; color:#555;'>🖼️ ").append(escapeHtml(msg.getFileName()))
                    .append(" (").append(msg.getFormattedFileSize()).append(")</span> &nbsp; ");
            sb.append("<a href='preview:").append(msg.getMessageId()).append("' style='color:#007bff; font-size:11px;'>[🔍 Xem phóng to]</a> &nbsp; ");
            sb.append("<a href='download:").append(msg.getMessageId()).append("' style='color:#28a745; font-size:11px;'>[💾 Lưu ảnh]</a>");
            sb.append("</div>");
            if (msg.getContent() != null && !msg.getContent().isEmpty() && !msg.getContent().equals(msg.getFileName())) {
                sb.append("<div style='margin-top:2px; font-style:italic;'>").append(escapeHtml(msg.getContent())).append("</div>");
            }

        } else if (msg.isFile()) {
            sb.append("<div style='margin:4px 0; padding:6px 10px; background-color:#e9ecef; border:1px solid #ced4da; border-radius:4px;'>");
            sb.append("<b>📄 Tệp tin:</b> <span style='color:#0d6efd;'>").append(escapeHtml(msg.getFileName()))
                    .append("</span> (").append(msg.getFormattedFileSize()).append(")<br>");
            if (msg.getContent() != null && !msg.getContent().isEmpty() && !msg.getContent().equals(msg.getFileName())) {
                sb.append("<i>Chú thích: ").append(escapeHtml(msg.getContent())).append("</i><br>");
            }
            sb.append("<a href='download:").append(msg.getMessageId()).append("' style='color:#0d6efd; font-weight:bold; font-size:12px;'>💾 Nhấn vào đây để tải tệp xuống</a>");
            sb.append("</div>");

        } else if (msg.isAudio()) {
            sb.append("<div style='margin:4px 0; padding:6px 10px; background-color:#e8f4fd; border:1px solid #b6d4fe; border-radius:4px;'>");
            sb.append("<b>🎵 Tin nhắn thoại / Audio:</b> <span style='color:#084298;'>").append(escapeHtml(msg.getFileName()))
                    .append("</span> (").append(msg.getFormattedFileSize()).append(")<br>");
            if (msg.getContent() != null && !msg.getContent().isEmpty() && !msg.getContent().equals(msg.getFileName())) {
                sb.append("<i>Lời nhắn: ").append(escapeHtml(msg.getContent())).append("</i><br>");
            }
            sb.append("<a href='play:").append(msg.getMessageId()).append("' style='color:#198754; font-weight:bold; font-size:12px;'>▶ Phát âm thanh</a> &nbsp;|&nbsp; ");
            sb.append("<a href='stop:").append(msg.getMessageId()).append("' style='color:#dc3545; font-size:12px;'>⏹ Dừng</a> &nbsp;|&nbsp; ");
            sb.append("<a href='download:").append(msg.getMessageId()).append("' style='color:#0d6efd; font-size:12px;'>💾 Tải về</a>");
            sb.append("</div>");

        } else {
            // Tin nhắn văn bản thuần túy
            sb.append(escapeHtml(msg.getContent()));
        }

        return sb.toString();
    }

    private void handleHyperlinkClick(String desc) {
        if (desc == null) return;

        if (desc.startsWith("preview:")) {
            String msgId = desc.substring("preview:".length());
            ChatMessage msg = messageCache.get(msgId);
            if (msg != null && msg.getFileData() != null) {
                ImagePreviewDialog dlg = new ImagePreviewDialog(this, msg.getFileData(), msg.getFileName());
                dlg.setVisible(true);
            }
        } else if (desc.startsWith("download:")) {
            String msgId = desc.substring("download:".length());
            ChatMessage msg = messageCache.get(msgId);
            if (msg != null && msg.getFileData() != null) {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("Lưu tệp về máy tính");
                fc.setSelectedFile(new File(msg.getFileName() != null ? msg.getFileName() : "downloaded_file.bin"));
                int res = fc.showSaveDialog(this);
                if (res == JFileChooser.APPROVE_OPTION) {
                    try {
                        Files.write(fc.getSelectedFile().toPath(), msg.getFileData());
                        JOptionPane.showMessageDialog(this, "Tải tệp thành công:\n" + fc.getSelectedFile().getAbsolutePath());
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(this, "Lỗi khi lưu tệp: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        } else if (desc.startsWith("play:")) {
            String msgId = desc.substring("play:".length());
            ChatMessage msg = messageCache.get(msgId);
            if (msg != null && msg.getFileData() != null) {
                playAudioBytes(msg.getFileData());
            }
        } else if (desc.startsWith("stop:")) {
            stopAudioPlayback();
        }
    }

    private void playAudioBytes(byte[] audioBytes) {
        stopAudioPlayback();
        new Thread(() -> {
            try {
                AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioBytes));
                activeAudioClip = AudioSystem.getClip();
                activeAudioClip.open(ais);
                activeAudioClip.start();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Lỗi phát âm thanh: " + e.getMessage() + "\n(Gợi ý: Tải file về máy để mở bằng trình phát mặc định)",
                        "Lỗi âm thanh", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void stopAudioPlayback() {
        if (activeAudioClip != null) {
            try {
                if (activeAudioClip.isRunning()) {
                    activeAudioClip.stop();
                }
                activeAudioClip.close();
            } catch (Exception ignored) {}
            activeAudioClip = null;
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
            chatPane.setText("");
            htmlDoc = (HTMLDocument) htmlKit.createDefaultDocument();
            chatPane.setDocument(htmlDoc);
        } catch (Exception ignored) {}
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", "<br>");
    }
}

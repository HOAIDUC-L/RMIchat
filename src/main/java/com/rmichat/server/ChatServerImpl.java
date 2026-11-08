package com.rmichat.server;

import com.rmichat.common.ChatGroup;
import com.rmichat.common.ChatMessage;
import com.rmichat.common.Constants;
import com.rmichat.interfaces.ChatClientCallback;
import com.rmichat.interfaces.ChatServerInterface;
import com.rmichat.interfaces.ServerSyncInterface;

import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lớp cài đặt chính của Server, triển khai cả ChatServerInterface (cho Client)
 * và ServerSyncInterface (cho Server đối tác).
 */
public class ChatServerImpl extends UnicastRemoteObject implements ChatServerInterface, ServerSyncInterface {
    private static final long serialVersionUID = 1L;

    private final String serverId;
    private final int localPort;
    private final String partnerHost;
    private final int partnerPort;
    private final String partnerServerId;

    // Danh sách Client cục bộ đang kết nối vào Server này: username -> Callback Stub
    private final ConcurrentHashMap<String, ChatClientCallback> localClients = new ConcurrentHashMap<>();

    // Tập hợp tên người dùng đang online ở Server đối tác
    private final Set<String> remoteOnlineUsers = ConcurrentHashMap.newKeySet();

    // Lịch sử tin nhắn của hệ thống
    private final List<ChatMessage> chatHistory = Collections.synchronizedList(new ArrayList<>());

    // Tập hợp các ID tin nhắn đã được xử lý - Dùng để chống xử lý trùng lặp và CHỐNG VÒNG LẶP ĐỒNG BỘ VÔ HẠN
    private final Set<String> processedMessageIds = ConcurrentHashMap.newKeySet();

    // Danh sách các Nhóm Chat trong hệ thống: groupId -> ChatGroup
    private final ConcurrentHashMap<String, ChatGroup> groups = new ConcurrentHashMap<>();

    // Stub tham chiếu tới Server đối tác
    private volatile ServerSyncInterface partnerSyncStub = null;
    private volatile boolean isPartnerConnected = false;

    // Quản lý cơ sở dữ liệu SQLite
    private final com.rmichat.database.DatabaseManager dbManager;

    // Danh sách các listener lắng nghe sự kiện để cập nhật Giao diện Quản trị
    private final List<ServerEventListener> eventListeners = new CopyOnWriteArrayList<>();

    // Scheduler cho các tác vụ nền: Heartbeat, tự động kết nối lại Server đối tác
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public ChatServerImpl(String serverId, int localPort, String partnerHost, int partnerPort) throws RemoteException {
        // Export trên localPort để thuận tiện cấu hình Firewall (thay vì dùng port 0 ngẫu nhiên)
        super(localPort);
        this.serverId = serverId;
        this.localPort = localPort;
        this.partnerHost = partnerHost;
        this.partnerPort = partnerPort;
        this.partnerServerId = serverId.contains("1") ? Constants.SERVER_2_ID : Constants.SERVER_1_ID;

        // Khởi tạo SQLite Database Manager cho Server này
        this.dbManager = new com.rmichat.database.DatabaseManager(serverId);

        // Nạp tin nhắn từ SQLite vào bộ nhớ cache RAM
        List<ChatMessage> savedMessages = dbManager.getAllMessages();
        for (ChatMessage msg : savedMessages) {
            chatHistory.add(msg);
            processedMessageIds.add(msg.getMessageId());
        }
        System.out.printf("[%s] [DATABASE] Đã nạp %d tin nhắn từ SQLite vào cache.%n", serverId, savedMessages.size());

        // Nạp danh sách Nhóm Chat từ SQLite vào RAM
        List<ChatGroup> savedGroups = dbManager.getAllGroups();
        for (ChatGroup g : savedGroups) {
            groups.put(g.getGroupId(), g);
        }
        System.out.printf("[%s] [DATABASE] Đã nạp %d nhóm chat từ SQLite vào cache.%n", serverId, savedGroups.size());

        // Khởi động luồng nền định kỳ kiểm tra / kết nối lại với Server đối tác
        startPartnerConnectionMonitor();
    }

    public void addEventListener(ServerEventListener listener) {
        if (listener != null) eventListeners.add(listener);
    }

    public void removeEventListener(ServerEventListener listener) {
        eventListeners.remove(listener);
    }

    public com.rmichat.database.DatabaseManager getDatabaseManager() {
        return dbManager;
    }

    public String getServerId() {
        return serverId;
    }

    public int getLocalPort() {
        return localPort;
    }

    public String getPartnerHost() {
        return partnerHost;
    }

    public int getPartnerPort() {
        return partnerPort;
    }

    public String getPartnerServerId() {
        return partnerServerId;
    }

    public boolean isPartnerConnected() {
        return isPartnerConnected;
    }

    public Map<String, ChatClientCallback> getLocalClients() {
        return localClients;
    }

    public Set<String> getRemoteOnlineUsers() {
        return remoteOnlineUsers;
    }

    public Map<String, ChatGroup> getGroupsMap() {
        return groups;
    }

    public int getLocalClientCount() {
        return localClients.size();
    }

    public int getRemoteUserCount() {
        return remoteOnlineUsers.size();
    }

    public int getTotalMessageCount() {
        return chatHistory.size();
    }

    public int getProcessedMessageIdCount() {
        return processedMessageIds.size();
    }

    // =========================================================================
    // IMPLEMENTATION: ChatServerInterface (Client gọi lên Server)
    // =========================================================================

    @Override
    public synchronized boolean registerClient(String username, ChatClientCallback callback) throws RemoteException {
        if (username == null || username.trim().isEmpty() || callback == null) {
            return false;
        }
        username = username.trim();

        // Kiểm tra xem tên này đã online ở server này hoặc server đối tác chưa
        if (localClients.containsKey(username) || remoteOnlineUsers.contains(username)) {
            System.out.printf("[%s] Từ chối đăng nhập: Tên '%s' đã tồn tại trong hệ thống.%n", serverId, username);
            return false;
        }

        localClients.put(username, callback);
        System.out.printf("[%s] [CLIENT JOIN] '%s' vừa kết nối thành công. Tổng client cục bộ: %d%n",
                serverId, username, localClients.size());

        // Ghi nhật ký vào SQLite & Đẩy sự kiện sang Admin GUI
        dbManager.logActivity(username, "ĐĂNG NHẬP", serverId);
        long now = System.currentTimeMillis();
        for (ServerEventListener l : eventListeners) {
            l.onUserJoined(username, serverId, now);
        }

        // Gửi thông báo chào mừng tới client vừa kết nối
        try {
            callback.onServerNotification(String.format("Chào mừng %s đến với %s! Hệ thống sẵn sàng.", username, serverId));
            // Đẩy danh sách nhóm chat của người dùng này về giao diện
            callback.onGroupListUpdated(getGroupsForUser(username));
        } catch (RemoteException e) {
            System.err.printf("[%s] Lỗi gửi thông báo chào mừng tới %s%n", serverId, username);
        }

        // Tạo tin nhắn JOIN và phát thông báo
        ChatMessage joinMsg = ChatMessage.createJoin(username, serverId);
        recordAndBroadcastLocal(joinMsg);

        // Đồng bộ tin nhắn JOIN và trạng thái user sang Server đối tác
        syncToPartner(joinMsg);
        syncUserStatusToPartner(username, true);

        // Cập nhật danh sách online cho tất cả client
        broadcastUserList();

        return true;
    }

    @Override
    public synchronized void unregisterClient(String username) throws RemoteException {
        if (username == null) return;
        username = username.trim();
        ChatClientCallback removed = localClients.remove(username);
        if (removed != null) {
            System.out.printf("[%s] [CLIENT LEAVE] '%s' vừa ngắt kết nối. Còn lại: %d%n",
                    serverId, username, localClients.size());

            dbManager.logActivity(username, "ĐĂNG XUẤT", serverId);
            long now = System.currentTimeMillis();
            for (ServerEventListener l : eventListeners) {
                l.onUserLeft(username, serverId, "Đăng xuất", now);
            }

            ChatMessage leaveMsg = ChatMessage.createLeave(username, serverId);
            recordAndBroadcastLocal(leaveMsg);

            // Đồng bộ sang Server đối tác
            syncToPartner(leaveMsg);
            syncUserStatusToPartner(username, false);

            broadcastUserList();
        }
    }

    @Override
    public void sendMessage(ChatMessage message) throws RemoteException {
        if (message == null) return;

        // Đảm bảo tin nhắn mang nguồn gốc server này nếu chưa có
        if (message.getOriginServer() == null || message.getOriginServer().isEmpty()) {
            message.setOriginServer(this.serverId);
        }

        // Kiểm tra chống lặp: Nếu messageId đã xử lý rồi thì bỏ qua
        if (!processedMessageIds.add(message.getMessageId())) {
            System.out.printf("[%s] Tin nhắn trùng lặp bị bỏ qua (ID: %s)%n", serverId, message.getMessageId());
            return;
        }

        // Lưu tin nhắn vào SQLite và lịch sử bộ nhớ cache
        chatHistory.add(message);
        dbManager.saveMessage(message);

        // Đẩy sự kiện tới Admin GUI
        for (ServerEventListener l : eventListeners) {
            l.onMessageEvent(message);
        }

        if (message.isGroup()) {
            // --- XỬ LÝ TIN NHẮN NHÓM CHAT (GROUP CHAT) ---
            String groupId = message.getGroupId();
            ChatGroup group = groups.get(groupId);
            if (group == null || !group.isMember(message.getSender())) {
                ChatClientCallback senderCb = localClients.get(message.getSender());
                if (senderCb != null) {
                    try {
                        senderCb.onServerNotification("Lỗi: Nhóm không tồn tại hoặc bạn không còn là thành viên của nhóm này.");
                    } catch (RemoteException ignored) {}
                }
                return;
            }

            System.out.printf("[%s] [GROUP CHAT] [%s] từ %s: %s%n",
                    serverId, group.getGroupName(), message.getSender(), message.getContent());

            // 1. Phân phối cho tất cả thành viên trong nhóm đang kết nối cục bộ tại Server này
            for (String member : group.getMembers()) {
                ChatClientCallback cb = localClients.get(member);
                if (cb != null) {
                    try {
                        cb.onReceiveMessage(message);
                    } catch (RemoteException e) {
                        handleDeadClient(member);
                    }
                }
            }

            // 2. Đồng bộ sang Server đối tác qua RMI (đối tác sẽ chỉ phân phối cho thành viên nhóm bên họ)
            syncToPartner(message);

        } else if (message.isPrivate()) {
            // --- XỬ LÝ TIN NHẮN RIÊNG TƯ (1-1 / WHISPER) ---
            String recipient = message.getRecipient();
            System.out.printf("[%s] [PRIVATE CHAT] từ %s tới %s: %s%n",
                    serverId, message.getSender(), recipient, message.getContent());

            // 1. Gửi lại cho chính người gửi (để hiển thị tin nhắn riêng vừa gửi trên UI)
            ChatClientCallback senderCb = localClients.get(message.getSender());
            if (senderCb != null) {
                try {
                    senderCb.onReceiveMessage(message);
                } catch (RemoteException e) {
                    handleDeadClient(message.getSender());
                }
            }

            // 2. Phân phối tới người nhận
            if (localClients.containsKey(recipient)) {
                // Người nhận nằm ngay tại Server này
                ChatClientCallback recCb = localClients.get(recipient);
                if (recCb != null) {
                    try {
                        recCb.onReceiveMessage(message);
                    } catch (RemoteException e) {
                        handleDeadClient(recipient);
                    }
                }
            } else if (remoteOnlineUsers.contains(recipient)) {
                // Người nhận ở Server đối tác -> Đồng bộ sang Server đối tác qua RMI
                syncToPartner(message);
            } else {
                // Người nhận không online
                if (senderCb != null) {
                    try {
                        senderCb.onServerNotification("Người dùng '" + recipient + "' hiện không online.");
                    } catch (RemoteException ignored) {}
                }
            }
        } else {
            // --- XỬ LÝ TIN NHẮN PHÒNG CHUNG (PUBLIC BROADCAST) ---
            System.out.printf("[%s] [LOCAL CHAT] từ %s: %s%n", serverId, message.getSender(), message.getContent());

            // Đẩy tin nhắn tới tất cả client đang kết nối tại Server này (RMI Callback)
            broadcastToLocalClients(message);

            // ĐỒNG BỘ SANG SERVER ĐỐI TÁC QUA RMI
            syncToPartner(message);
        }
    }

    @Override
    public List<String> getOnlineUsers() throws RemoteException {
        Set<String> allUsers = new TreeSet<>();
        allUsers.addAll(localClients.keySet());
        allUsers.addAll(remoteOnlineUsers);
        return new ArrayList<>(allUsers);
    }

    @Override
    public List<ChatMessage> getHistory() throws RemoteException {
        synchronized (chatHistory) {
            return new ArrayList<>(chatHistory);
        }
    }

    // =========================================================================
    // QUẢN LÝ NHÓM CHAT (GROUP CHAT METHODS)
    // =========================================================================

    @Override
    public ChatGroup createGroup(String groupName, String creator, List<String> initialMembers) throws RemoteException {
        if (groupName == null || groupName.trim().isEmpty() || creator == null) return null;
        ChatGroup group = new ChatGroup(UUID.randomUUID().toString(), groupName.trim(), creator.trim(), initialMembers, System.currentTimeMillis());
        groups.put(group.getGroupId(), group);
        dbManager.saveGroup(group);
        dbManager.logActivity(creator, "TẠO NHÓM: " + groupName, serverId);
        System.out.printf("[%s] [TẠO NHÓM] '%s' tạo nhóm '%s' (ID: %s, Thành viên: %d)%n",
                serverId, creator, groupName, group.getGroupId(), group.getMemberCount());

        // Cập nhật danh sách nhóm cho các thành viên cục bộ
        broadcastGroupListToMembers(group);

        // Đồng bộ tạo nhóm sang Server đối tác
        if (partnerSyncStub != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    partnerSyncStub.syncGroupCreate(group);
                } catch (RemoteException e) {
                    System.err.printf("[%s] Lỗi đồng bộ tạo nhóm sang đối tác: %s%n", serverId, e.getMessage());
                }
            });
        }

        // Gửi tin nhắn chào mừng trong nhóm
        ChatMessage welcomeMsg = ChatMessage.createGroupChat("HỆ THỐNG", group.getGroupId(), group.getGroupName(),
                String.format("Nhóm '%s' đã được tạo bởi %s.", group.getGroupName(), creator), serverId);
        sendMessage(welcomeMsg);

        return group;
    }

    @Override
    public boolean inviteToGroup(String groupId, String inviter, String targetUser) throws RemoteException {
        if (groupId == null || targetUser == null) return false;
        ChatGroup group = groups.get(groupId);
        if (group == null) return false;
        if (group.isMember(targetUser)) return false;

        group.addMember(targetUser);
        dbManager.addGroupMember(groupId, targetUser);
        dbManager.logActivity(inviter, "MỜI VÀO NHÓM: " + targetUser, serverId);
        System.out.printf("[%s] [MỜI NHÓM] '%s' mời '%s' vào nhóm '%s'%n",
                serverId, inviter, targetUser, group.getGroupName());

        broadcastGroupListToMembers(group);

        if (partnerSyncStub != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    partnerSyncStub.syncGroupUpdate(group, inviter, "INVITE");
                } catch (RemoteException e) {
                    System.err.printf("[%s] Lỗi đồng bộ cập nhật nhóm sang đối tác: %s%n", serverId, e.getMessage());
                }
            });
        }

        ChatMessage inviteNotice = ChatMessage.createGroupChat("HỆ THỐNG", group.getGroupId(), group.getGroupName(),
                String.format("%s đã mời %s vào nhóm.", inviter, targetUser), serverId);
        sendMessage(inviteNotice);

        return true;
    }

    @Override
    public boolean leaveGroup(String groupId, String username) throws RemoteException {
        if (groupId == null || username == null) return false;
        ChatGroup group = groups.get(groupId);
        if (group == null || !group.isMember(username)) return false;

        group.removeMember(username);
        dbManager.removeGroupMember(groupId, username);
        dbManager.logActivity(username, "RỜI NHÓM: " + group.getGroupName(), serverId);
        System.out.printf("[%s] [RỜI NHÓM] '%s' đã rời khỏi nhóm '%s'%n",
                serverId, username, group.getGroupName());

        // Cập nhật cho người rời nhóm (để nhóm biến mất khỏi danh sách của họ)
        broadcastGroupListToUser(username);
        // Cập nhật cho các thành viên còn lại
        broadcastGroupListToMembers(group);

        if (partnerSyncStub != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    partnerSyncStub.syncGroupUpdate(group, username, "LEAVE");
                } catch (RemoteException e) {
                    System.err.printf("[%s] Lỗi đồng bộ rời nhóm sang đối tác: %s%n", serverId, e.getMessage());
                }
            });
        }

        ChatMessage leaveNotice = ChatMessage.createGroupChat("HỆ THỐNG", group.getGroupId(), group.getGroupName(),
                String.format("%s đã rời khỏi nhóm.", username), serverId);
        sendMessage(leaveNotice);

        return true;
    }

    @Override
    public List<ChatGroup> getGroupsForUser(String username) throws RemoteException {
        if (username == null) return Collections.emptyList();
        List<ChatGroup> result = new ArrayList<>();
        for (ChatGroup g : groups.values()) {
            if (g.isMember(username)) {
                result.add(g);
            }
        }
        return result;
    }

    @Override
    public ChatGroup getGroup(String groupId) throws RemoteException {
        return groupId != null ? groups.get(groupId) : null;
    }

    // =========================================================================
    // IMPLEMENTATION: ServerSyncInterface (Server đối tác gọi sang)
    // =========================================================================

    @Override
    public void syncMessage(ChatMessage message) throws RemoteException {
        if (message == null) return;

        // [CƠ CHẾ CHỐNG VÒNG LẶP VÔ HẠN (ANTI-INFINITE LOOP)]
        // Nếu ID tin nhắn này đã tồn tại trong tập hợp đã xử lý, BỎ QUA NGAY LẬP TỨC!
        if (!processedMessageIds.add(message.getMessageId())) {
            System.out.printf("[%s] [SYNC CHỐNG LẶP] Bỏ qua tin nhắn đã xử lý: %s%n",
                    serverId, message.getMessageId());
            for (ServerEventListener l : eventListeners) {
                l.onSyncDuplicateBlocked(message.getMessageId(), message.getOriginServer());
            }
            return;
        }

        // Lưu vào SQLite và lịch sử Server này
        chatHistory.add(message);
        dbManager.saveMessage(message);
        System.out.printf("[%s] [SYNC NHẬN TỪ %s] [%s]: %s%n",
                serverId, message.getOriginServer(), message.getSender(), message.getContent());

        // Báo Admin GUI
        for (ServerEventListener l : eventListeners) {
            l.onSyncReceived(message, message.getOriginServer());
            l.onMessageEvent(message);
        }

        if (message.isGroup()) {
            // Tin nhắn nhóm từ đối tác: chỉ chuyển tiếp DUY NHẤT cho các thành viên nhóm ở server này
            String groupId = message.getGroupId();
            ChatGroup group = groups.get(groupId);
            if (group != null) {
                for (String member : group.getMembers()) {
                    ChatClientCallback cb = localClients.get(member);
                    if (cb != null) {
                        try {
                            cb.onReceiveMessage(message);
                        } catch (RemoteException e) {
                            handleDeadClient(member);
                        }
                    }
                }
            }
        } else if (message.isPrivate()) {
            // Tin nhắn riêng tư từ đối tác: chỉ chuyển tiếp DUY NHẤT cho người nhận
            String recipient = message.getRecipient();
            ChatClientCallback recCb = localClients.get(recipient);
            if (recCb != null) {
                try {
                    recCb.onReceiveMessage(message);
                } catch (RemoteException e) {
                    handleDeadClient(recipient);
                }
            }
        } else {
            // Tin phòng chung: Đẩy tin nhắn tới các Client cục bộ của Server này
            broadcastToLocalClients(message);
        }

        // QUAN TRỌNG: TUYỆT ĐỐI KHÔNG GỌI syncToPartner ở đây!
        // Vì tin nhắn này đến từ kênh đồng bộ, luồng đồng bộ kết thúc tại đây!
    }

    @Override
    public void syncUserStatus(String username, boolean isOnline, String fromServerId) throws RemoteException {
        if (username == null) return;
        long now = System.currentTimeMillis();
        if (isOnline) {
            remoteOnlineUsers.add(username);
            System.out.printf("[%s] [SYNC USER ONLINE] '%s' online tại %s%n", serverId, username, fromServerId);
            dbManager.logActivity(username, "ĐỒNG BỘ ONLINE", fromServerId);
            for (ServerEventListener l : eventListeners) {
                l.onUserJoined(username, fromServerId, now);
            }
        } else {
            remoteOnlineUsers.remove(username);
            System.out.printf("[%s] [SYNC USER OFFLINE] '%s' offline tại %s%n", serverId, username, fromServerId);
            dbManager.logActivity(username, "ĐỒNG BỘ OFFLINE", fromServerId);
            for (ServerEventListener l : eventListeners) {
                l.onUserLeft(username, fromServerId, "Offline từ đối tác", now);
            }
        }
        broadcastUserList();
    }

    @Override
    public void syncGroupCreate(ChatGroup group) throws RemoteException {
        if (group == null) return;
        groups.put(group.getGroupId(), group);
        dbManager.saveGroup(group);
        System.out.printf("[%s] [SYNC NHẬN TẠO NHÓM] Nhóm '%s' (ID: %s)%n",
                serverId, group.getGroupName(), group.getGroupId());
        broadcastGroupListToMembers(group);
    }

    @Override
    public void syncGroupUpdate(ChatGroup group, String actionUser, String actionType) throws RemoteException {
        if (group == null) return;
        groups.put(group.getGroupId(), group);
        dbManager.saveGroup(group);
        System.out.printf("[%s] [SYNC NHẬN CẬP NHẬT NHÓM] Nhóm '%s' bởi %s (%s)%n",
                serverId, group.getGroupName(), actionUser, actionType);
        broadcastGroupListToMembers(group);
        if ("LEAVE".equals(actionType) && actionUser != null) {
            broadcastGroupListToUser(actionUser);
        }
    }

    @Override
    public List<ChatGroup> requestFullGroups() throws RemoteException {
        return new ArrayList<>(groups.values());
    }

    @Override
    public boolean ping() throws RemoteException {
        return true;
    }

    @Override
    public List<ChatMessage> requestFullHistory() throws RemoteException {
        synchronized (chatHistory) {
            return new ArrayList<>(chatHistory);
        }
    }

    @Override
    public List<String> getLocalOnlineUsers() throws RemoteException {
        return new ArrayList<>(localClients.keySet());
    }

    // =========================================================================
    // HÀM TIỆN ÍCH NỘI BỘ (BROADCAST & SYNC HELPER)
    // =========================================================================

    private void recordAndBroadcastLocal(ChatMessage msg) {
        if (processedMessageIds.add(msg.getMessageId())) {
            chatHistory.add(msg);
            dbManager.saveMessage(msg);
            for (ServerEventListener l : eventListeners) {
                l.onMessageEvent(msg);
            }
            broadcastToLocalClients(msg);
        }
    }

    private void broadcastGroupListToUser(String username) {
        if (username == null) return;
        ChatClientCallback cb = localClients.get(username);
        if (cb != null) {
            try {
                cb.onGroupListUpdated(getGroupsForUser(username));
            } catch (RemoteException e) {
                handleDeadClient(username);
            }
        }
    }

    private void broadcastGroupListToMembers(ChatGroup group) {
        if (group == null) return;
        for (String member : group.getMembers()) {
            broadcastGroupListToUser(member);
        }
    }

    /**
     * Duyệt danh sách Client cục bộ và gọi RMI Callback.
     * Tự động phát hiện và loại bỏ Client đã chết (tắt ứng dụng đột ngột).
     */
    private void broadcastToLocalClients(ChatMessage message) {
        List<String> disconnectedClients = new ArrayList<>();

        for (Map.Entry<String, ChatClientCallback> entry : localClients.entrySet()) {
            String user = entry.getKey();
            ChatClientCallback callback = entry.getValue();
            try {
                callback.onReceiveMessage(message);
            } catch (RemoteException e) {
                System.err.printf("[%s] Client '%s' mất kết nối (Callback failed). Đánh dấu loại bỏ.%n",
                        serverId, user);
                disconnectedClients.add(user);
            }
        }

        // Xử lý các client bị ngắt kết nối đột ngột
        for (String deadUser : disconnectedClients) {
            handleDeadClient(deadUser);
        }
    }

    private void broadcastUserList() {
        List<String> allUsers = null;
        try {
            allUsers = getOnlineUsers();
        } catch (RemoteException ignored) {}

        if (allUsers == null) return;

        List<String> disconnectedClients = new ArrayList<>();
        for (Map.Entry<String, ChatClientCallback> entry : localClients.entrySet()) {
            String user = entry.getKey();
            ChatClientCallback callback = entry.getValue();
            try {
                callback.onUserListUpdated(allUsers);
            } catch (RemoteException e) {
                disconnectedClients.add(user);
            }
        }

        for (String deadUser : disconnectedClients) {
            handleDeadClient(deadUser);
        }
    }

    private void handleDeadClient(String username) {
        localClients.remove(username);
        long now = System.currentTimeMillis();
        dbManager.logActivity(username, "MẤT KẾT NỐI", serverId);
        for (ServerEventListener l : eventListeners) {
            l.onUserLeft(username, serverId, "Mất kết nối", now);
        }

        // Báo cho các client khác
        ChatMessage leaveMsg = ChatMessage.createLeave(username, serverId);
        recordAndBroadcastLocal(leaveMsg);

        // Báo cho Server đối tác
        syncToPartner(leaveMsg);
        syncUserStatusToPartner(username, false);

        broadcastUserList();
    }

    /**
     * Gọi RMI đồng bộ tin nhắn sang Server đối tác.
     */
    private void syncToPartner(ChatMessage message) {
        if (partnerSyncStub == null) {
            // Server đối tác hiện chưa online, tin nhắn đã được lưu trong lịch sử cục bộ
            // Khi đối tác kết nối lại, cơ chế requestFullHistory sẽ lấy lại
            return;
        }

        // Thực thi gọi RMI bất đồng bộ để không làm nghẽn tiến trình của Client gửi tin
        CompletableFuture.runAsync(() -> {
            try {
                partnerSyncStub.syncMessage(message);
                System.out.printf("[%s] -> [ĐÃ ĐỒNG BỘ SANG %s] Tin nhắn ID: %s%n",
                        serverId, partnerServerId, message.getMessageId().substring(0, 8));
                for (ServerEventListener l : eventListeners) {
                    l.onSyncSent(message, partnerServerId);
                }
            } catch (RemoteException e) {
                System.err.printf("[%s] Lỗi khi gọi RMI đồng bộ sang %s: %s%n",
                        serverId, partnerServerId, e.getMessage());
                partnerSyncStub = null;
                isPartnerConnected = false;
                for (ServerEventListener l : eventListeners) {
                    l.onPartnerStatusChanged(false, partnerHost + ":" + partnerPort);
                }
            }
        });
    }

    private void syncUserStatusToPartner(String username, boolean isOnline) {
        if (partnerSyncStub == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                partnerSyncStub.syncUserStatus(username, isOnline, serverId);
            } catch (RemoteException e) {
                System.err.printf("[%s] Lỗi đồng bộ trạng thái user sang %s: %s%n",
                        serverId, partnerServerId, e.getMessage());
                partnerSyncStub = null;
                isPartnerConnected = false;
                for (ServerEventListener l : eventListeners) {
                    l.onPartnerStatusChanged(false, partnerHost + ":" + partnerPort);
                }
            }
        });
    }

    /**
     * Quản lý trạng thái kết nối với Server đối tác:
     * Định kỳ thử kết nối lại nếu chưa có Stub hoặc bị đứt liên lạc.
     * Khi kết nối thành công, đồng bộ lại lịch sử tin nhắn, nhóm chat và danh sách user.
     */
    private void startPartnerConnectionMonitor() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (partnerSyncStub != null) {
                    // Kiểm tra heartbeat
                    partnerSyncStub.ping();
                    if (!isPartnerConnected) {
                        isPartnerConnected = true;
                        System.out.printf("[%s] [HEARTBEAT OK] Đã tái kết nối với %s (%s:%d)%n",
                                serverId, partnerServerId, partnerHost, partnerPort);
                        broadcastNotification(String.format("Đã kết nối lại với %s. Dữ liệu đã được đồng bộ.", partnerServerId));
                        for (ServerEventListener l : eventListeners) {
                            l.onPartnerStatusChanged(true, partnerHost + ":" + partnerPort);
                        }
                    }
                } else {
                    // Cố gắng tìm và kết nối tới Server đối tác
                    String partnerUrl = String.format("rmi://%s:%d/%s",
                            partnerHost, partnerPort, Constants.SYNC_SERVICE_NAME);
                    ServerSyncInterface stub = (ServerSyncInterface) Naming.lookup(partnerUrl);

                    if (stub != null && stub.ping()) {
                        partnerSyncStub = stub;
                        isPartnerConnected = true;
                        System.out.printf("[%s] [KẾT NỐI ĐỐI TÁC THÀNH CÔNG] Đã liên kết với %s tại %s:%d%n",
                                serverId, partnerServerId, partnerHost, partnerPort);

                        // Bù đắp dữ liệu: Lấy lịch sử từ đối tác về để đồng bộ những tin nhắn đã bỏ lỡ
                        catchUpWithPartner(stub);
                        broadcastNotification(String.format("Đã liên kết đồng bộ với %s! Dữ liệu 2 server hiện đã đồng bộ.", partnerServerId));
                        for (ServerEventListener l : eventListeners) {
                            l.onPartnerStatusChanged(true, partnerHost + ":" + partnerPort);
                        }
                    }
                }
            } catch (Exception e) {
                if (isPartnerConnected) {
                    System.err.printf("[%s] [MẤT LIÊN LẠC ĐỐI TÁC] %s không phản hồi: %s%n",
                            serverId, partnerServerId, e.getMessage());
                    broadcastNotification(String.format("Mất liên kết với %s. Tin nhắn sẽ tự động đồng bộ khi đối tác bật lại.", partnerServerId));
                    for (ServerEventListener l : eventListeners) {
                        l.onPartnerStatusChanged(false, partnerHost + ":" + partnerPort);
                    }
                }
                partnerSyncStub = null;
                isPartnerConnected = false;
            }
        }, 1, 3, TimeUnit.SECONDS);
    }

    private void catchUpWithPartner(ServerSyncInterface stub) {
        try {
            // 1. Đồng bộ lịch sử tin nhắn
            List<ChatMessage> partnerHistory = stub.requestFullHistory();
            List<ChatMessage> newMessages = new ArrayList<>();
            if (partnerHistory != null) {
                for (ChatMessage msg : partnerHistory) {
                    if (processedMessageIds.add(msg.getMessageId())) {
                        chatHistory.add(msg);
                        dbManager.saveMessage(msg);
                        newMessages.add(msg);
                    }
                }
            }
            if (!newMessages.isEmpty()) {
                chatHistory.sort(Comparator.comparingLong(ChatMessage::getTimestamp));
                System.out.printf("[%s] [BÙ ĐẮP DỮ LIỆU] Đã nạp thêm %d tin nhắn từ %s.%n",
                        serverId, newMessages.size(), partnerServerId);
                // Đẩy các tin nhắn bù đắp tới các client cục bộ
                for (ChatMessage msg : newMessages) {
                    broadcastToLocalClients(msg);
                }
            }

            // 2. Đồng bộ danh sách online users
            List<String> partnerUsers = stub.getLocalOnlineUsers();
            remoteOnlineUsers.clear();
            if (partnerUsers != null) {
                remoteOnlineUsers.addAll(partnerUsers);
            }
            broadcastUserList();

            // 3. Đồng bộ danh sách Nhóm Chat
            List<ChatGroup> partnerGroups = stub.requestFullGroups();
            if (partnerGroups != null) {
                for (ChatGroup pg : partnerGroups) {
                    ChatGroup localG = groups.get(pg.getGroupId());
                    if (localG == null) {
                        groups.put(pg.getGroupId(), pg);
                        dbManager.saveGroup(pg);
                        broadcastGroupListToMembers(pg);
                    } else {
                        // Hợp nhất thành viên
                        for (String m : pg.getMembers()) {
                            if (localG.addMember(m)) {
                                dbManager.addGroupMember(localG.getGroupId(), m);
                            }
                        }
                        broadcastGroupListToMembers(localG);
                    }
                }
            }

        } catch (Exception e) {
            System.err.printf("[%s] Lỗi khi nạp dữ liệu bù đắp từ đối tác: %s%n", serverId, e.getMessage());
        }
    }

    private void broadcastNotification(String notice) {
        List<String> deadClients = new ArrayList<>();
        for (Map.Entry<String, ChatClientCallback> entry : localClients.entrySet()) {
            try {
                entry.getValue().onServerNotification(notice);
            } catch (RemoteException e) {
                deadClients.add(entry.getKey());
            }
        }
        for (String deadUser : deadClients) {
            handleDeadClient(deadUser);
        }
    }

    // =========================================================================
    // CÁC HÀM DÀNH RIÊNG CHO QUẢN TRỊ VIÊN (ADMIN DASHBOARD ACTIONS)
    // =========================================================================

    /**
     * Quản trị viên kick một người dùng ra khỏi hệ thống.
     */
    public synchronized void kickUser(String username) {
        if (username == null) return;
        username = username.trim();
        ChatClientCallback cb = localClients.get(username);
        if (cb != null) {
            try {
                cb.onServerNotification("Bạn đã bị Quản trị viên ngắt kết nối khỏi hệ thống.");
            } catch (Exception ignored) {}
            try {
                unregisterClient(username);
            } catch (Exception ignored) {}
            dbManager.logActivity(username, "BỊ KICK BỞI ADMIN", serverId);
            System.out.printf("[%s] [ADMIN KICK] Quản trị viên đã kick user '%s'%n", serverId, username);
        }
    }

    /**
     * Quản trị viên phát thanh thông báo khẩn tới toàn bộ Client ở cả 2 Server.
     */
    public void broadcastAdminAnnouncement(String notice) {
        if (notice == null || notice.trim().isEmpty()) return;
        ChatMessage adminMsg = new ChatMessage("ADMIN (" + serverId + ")", notice.trim(),
                ChatMessage.MessageType.SYSTEM, serverId);
        try {
            sendMessage(adminMsg);
        } catch (RemoteException e) {
            System.err.println("[ADMIN] Phát thông báo thất bại: " + e.getMessage());
        }
    }

    /**
     * Quản trị viên kích hoạt cưỡng chế đồng bộ lại với Server đối tác.
     */
    public void triggerForceSync() {
        if (partnerSyncStub != null) {
            catchUpWithPartner(partnerSyncStub);
        }
    }

    public boolean isPartnerServerConnected() {
        return isPartnerConnected;
    }
}

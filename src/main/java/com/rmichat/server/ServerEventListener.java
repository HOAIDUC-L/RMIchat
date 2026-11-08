package com.rmichat.server;

import com.rmichat.common.ChatMessage;

/**
 * Interface lắng nghe sự kiện từ ChatServerImpl để cập nhật Trang Quản Trị (Server Admin GUI).
 */
public interface ServerEventListener {

    /**
     * Có người dùng mới đăng nhập.
     */
    void onUserJoined(String username, String originServer, long timestamp);

    /**
     * Người dùng rời khỏi hoặc bị ngắt kết nối.
     */
    void onUserLeft(String username, String originServer, String reason, long timestamp);

    /**
     * Nhận tin nhắn từ Client hoặc từ Server đối tác.
     */
    void onMessageEvent(ChatMessage message);

    /**
     * Gửi tin nhắn đồng bộ sang Server đối tác.
     */
    void onSyncSent(ChatMessage message, String targetServer);

    /**
     * Nhận tin nhắn đồng bộ từ Server đối tác.
     */
    void onSyncReceived(ChatMessage message, String fromServer);

    /**
     * Phát hiện và chặn gói tin đồng bộ bị trùng lặp (Anti-Infinite Loop).
     */
    void onSyncDuplicateBlocked(String messageId, String originServer);

    /**
     * Trạng thái kết nối với Server đối tác thay đổi.
     */
    void onPartnerStatusChanged(boolean connected, String partnerInfo);
}

package com.rmichat.common;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Lớp đại diện cho một tin nhắn trong hệ thống RMI Chat.
 * Bắt buộc implement Serializable để truyền nhận qua mạng giữa Client và Server cũng như giữa 2 Server.
 * Hỗ trợ cả tin nhắn công khai (Public) và tin nhắn riêng tư (Private/Direct Message).
 */
public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        CHAT,       // Tin nhắn phòng chat chung
        PRIVATE,    // Tin nhắn riêng (1-1 / Whisper)
        SYSTEM,     // Thông báo hệ thống (bảo trì, kết nối server...)
        JOIN,       // Thông báo người dùng mới tham gia
        LEAVE       // Thông báo người dùng rời phòng
    }

    private final String messageId;
    private final String sender;
    private final String content;
    private final long timestamp;
    private String originServer; // Server ban đầu nhận tin (ví dụ: "Server 1" hoặc "Server 2")
    private final MessageType type;
    private String recipient;    // Người nhận (nếu là tin nhắn riêng), null nếu là tin phòng chung

    public ChatMessage(String sender, String content, MessageType type, String originServer) {
        this(UUID.randomUUID().toString(), sender, null, content, type, originServer, System.currentTimeMillis());
    }

    public ChatMessage(String messageId, String sender, String content, MessageType type, String originServer, long timestamp) {
        this(messageId, sender, null, content, type, originServer, timestamp);
    }

    public ChatMessage(String messageId, String sender, String recipient, String content, MessageType type, String originServer, long timestamp) {
        this.messageId = messageId != null ? messageId : UUID.randomUUID().toString();
        this.sender = sender;
        this.recipient = recipient;
        this.content = content;
        this.type = type != null ? type : MessageType.CHAT;
        this.originServer = originServer;
        this.timestamp = timestamp;
    }

    // Factory methods tiện lợi
    public static ChatMessage createChat(String sender, String content, String originServer) {
        return new ChatMessage(sender, content, MessageType.CHAT, originServer);
    }

    public static ChatMessage createPrivate(String sender, String recipient, String content, String originServer) {
        return new ChatMessage(UUID.randomUUID().toString(), sender, recipient, content, MessageType.PRIVATE, originServer, System.currentTimeMillis());
    }

    public static ChatMessage createJoin(String sender, String originServer) {
        return new ChatMessage(sender, sender + " đã tham gia phòng chat.", MessageType.JOIN, originServer);
    }

    public static ChatMessage createLeave(String sender, String originServer) {
        return new ChatMessage(sender, sender + " đã rời phòng chat.", MessageType.LEAVE, originServer);
    }

    public static ChatMessage createSystem(String content, String originServer) {
        return new ChatMessage("HỆ THỐNG", content, MessageType.SYSTEM, originServer);
    }

    public boolean isPrivate() {
        return type == MessageType.PRIVATE || (recipient != null && !recipient.trim().isEmpty());
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new Date(timestamp));
    }

    public String getOriginServer() {
        return originServer;
    }

    public void setOriginServer(String originServer) {
        this.originServer = originServer;
    }

    public MessageType getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId);
    }

    @Override
    public String toString() {
        String target = isPrivate() ? " -> @" + recipient : "";
        return String.format("[%s][%s][%s%s]: %s (ID: %s)",
                getFormattedTime(), originServer, sender, target, content, messageId.substring(0, Math.min(8, messageId.length())));
    }
}

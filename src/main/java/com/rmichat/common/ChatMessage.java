package com.rmichat.common;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Lớp đại diện cho một tin nhắn trong hệ thống RMI Chat.
 * Bắt buộc implement Serializable để truyền nhận qua mạng giữa Client và Server cũng như giữa 2 Server.
 * Hỗ trợ tin nhắn công khai (Public), tin nhắn riêng tư (Private), tin nhắn nhóm (Group),
 * và tin nhắn đa phương tiện (Hình ảnh, Tệp tin, Âm thanh / Ghi âm).
 */
public class ChatMessage implements Serializable {
    private static final long serialVersionUID = 2L;

    public enum MessageType {
        CHAT,       // Tin nhắn phòng chat chung
        PRIVATE,    // Tin nhắn riêng (1-1 / Whisper)
        GROUP,      // Tin nhắn trong Nhóm Chat riêng
        IMAGE,      // Tin nhắn hình ảnh (PNG, JPG, GIF...)
        FILE,       // Tin nhắn tệp tin bất kỳ (PDF, DOCX, ZIP...)
        AUDIO,      // Tin nhắn âm thanh / Ghi âm giọng nói (WAV, MP3...)
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
    private String recipient;    // Người nhận (nếu là tin nhắn riêng), null nếu là tin phòng chung/nhóm
    private String groupId;      // ID Nhóm chat (nếu là tin gửi trong nhóm), null nếu là chat chung/riêng
    private String groupName;    // Tên Nhóm chat để hiển thị trên giao diện

    // Dữ liệu tệp đính kèm (cho IMAGE, FILE, AUDIO)
    private byte[] fileData;
    private String fileName;
    private long fileSize;

    // --- CÁC CONSTRUCTOR TƯƠNG THÍCH NGƯỢC ---
    public ChatMessage(String sender, String content, MessageType type, String originServer) {
        this(UUID.randomUUID().toString(), sender, null, content, type, originServer, System.currentTimeMillis());
    }

    public ChatMessage(String messageId, String sender, String content, MessageType type, String originServer, long timestamp) {
        this(messageId, sender, null, content, type, originServer, timestamp);
    }

    public ChatMessage(String messageId, String sender, String recipient, String content, MessageType type, String originServer, long timestamp) {
        this(messageId, sender, recipient, null, null, content, type, originServer, timestamp, null, null, 0);
    }

    // --- CONSTRUCTOR ĐẦY ĐỦ CHO MEDIA & GROUP ---
    public ChatMessage(String messageId, String sender, String recipient, String groupId, String groupName,
                       String content, MessageType type, String originServer, long timestamp,
                       byte[] fileData, String fileName, long fileSize) {
        this.messageId = messageId != null ? messageId : UUID.randomUUID().toString();
        this.sender = sender;
        this.recipient = recipient;
        this.groupId = groupId;
        this.groupName = groupName;
        this.content = content != null ? content : "";
        this.type = type != null ? type : MessageType.CHAT;
        this.originServer = originServer;
        this.timestamp = timestamp;
        this.fileData = fileData;
        this.fileName = fileName;
        this.fileSize = fileSize > 0 ? fileSize : (fileData != null ? fileData.length : 0);
    }

    // --- FACTORY METHODS TIỆN LỢI ---
    public static ChatMessage createChat(String sender, String content, String originServer) {
        return new ChatMessage(sender, content, MessageType.CHAT, originServer);
    }

    public static ChatMessage createPrivate(String sender, String recipient, String content, String originServer) {
        return new ChatMessage(UUID.randomUUID().toString(), sender, recipient, null, null,
                content, MessageType.PRIVATE, originServer, System.currentTimeMillis(), null, null, 0);
    }

    public static ChatMessage createGroupChat(String sender, String groupId, String groupName, String content, String originServer) {
        return new ChatMessage(UUID.randomUUID().toString(), sender, null, groupId, groupName,
                content, MessageType.GROUP, originServer, System.currentTimeMillis(), null, null, 0);
    }

    public static ChatMessage createImage(String sender, String recipient, String groupId, String groupName,
                                          String fileName, byte[] data, String caption, String originServer) {
        return new ChatMessage(UUID.randomUUID().toString(), sender, recipient, groupId, groupName,
                caption != null ? caption : fileName, MessageType.IMAGE, originServer,
                System.currentTimeMillis(), data, fileName, data != null ? data.length : 0);
    }

    public static ChatMessage createFile(String sender, String recipient, String groupId, String groupName,
                                         String fileName, byte[] data, String caption, String originServer) {
        return new ChatMessage(UUID.randomUUID().toString(), sender, recipient, groupId, groupName,
                caption != null ? caption : fileName, MessageType.FILE, originServer,
                System.currentTimeMillis(), data, fileName, data != null ? data.length : 0);
    }

    public static ChatMessage createAudio(String sender, String recipient, String groupId, String groupName,
                                          String fileName, byte[] data, String caption, String originServer) {
        return new ChatMessage(UUID.randomUUID().toString(), sender, recipient, groupId, groupName,
                caption != null ? caption : fileName, MessageType.AUDIO, originServer,
                System.currentTimeMillis(), data, fileName, data != null ? data.length : 0);
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

    // --- CÁC HÀM KIỂM TRA PHÂN LOẠI TIN NHẮN ---
    public boolean isPrivate() {
        return type == MessageType.PRIVATE || (recipient != null && !recipient.trim().isEmpty() && (groupId == null || groupId.trim().isEmpty()));
    }

    public boolean isGroup() {
        return type == MessageType.GROUP || (groupId != null && !groupId.trim().isEmpty());
    }

    public boolean isMedia() {
        return type == MessageType.IMAGE || type == MessageType.FILE || type == MessageType.AUDIO || fileData != null;
    }

    public boolean isImage() {
        return type == MessageType.IMAGE;
    }

    public boolean isFile() {
        return type == MessageType.FILE;
    }

    public boolean isAudio() {
        return type == MessageType.AUDIO;
    }

    // --- GETTERS & SETTERS ---
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

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
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

    public byte[] getFileData() {
        return fileData;
    }

    public void setFileData(byte[] fileData) {
        this.fileData = fileData;
        if (fileData != null) {
            this.fileSize = fileData.length;
        }
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFormattedFileSize() {
        if (fileSize <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(fileSize) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format("%.1f %s", fileSize / Math.pow(1024, digitGroups), units[digitGroups]);
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
        String target = "";
        if (isGroup()) {
            target = " -> [Nhóm: " + (groupName != null ? groupName : groupId) + "]";
        } else if (isPrivate()) {
            target = " -> @" + recipient;
        }
        String mediaTag = isMedia() ? "[" + type + ": " + fileName + " (" + getFormattedFileSize() + ")] " : "";
        return String.format("[%s][%s][%s%s]: %s%s (ID: %s)",
                getFormattedTime(), originServer, sender, target, mediaTag, content, messageId.substring(0, Math.min(8, messageId.length())));
    }
}

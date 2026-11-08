package com.rmichat.interfaces;

import com.rmichat.common.ChatMessage;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface RMI Đồng bộ giữa 2 Server (Server-to-Server P2P Sync).
 * Cho phép Server 1 gọi trực tiếp các hàm RMI trên Server 2 và ngược lại.
 */
public interface ServerSyncInterface extends Remote {

    /**
     * Nhận tin nhắn được đồng bộ từ Server đối tác.
     * Cực kỳ quan trọng: Hàm này xử lý lưu trữ và chuyển tiếp cho các Client thuộc Server này,
     * tuyệt đối KHÔNG gọi ngược lại Server đối tác để tránh vòng lặp vô hạn (Infinite Loop).
     * @param message Tin nhắn cần đồng bộ
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    void syncMessage(ChatMessage message) throws RemoteException;

    /**
     * Đồng bộ trạng thái đăng nhập / đăng xuất của người dùng giữa 2 Server.
     * @param username Tên người dùng
     * @param isOnline true nếu mới online, false nếu offline
     * @param serverId Tên server mà người dùng đó đang kết nối trực tiếp
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    void syncUserStatus(String username, boolean isOnline, String serverId) throws RemoteException;

    /**
     * Kiểm tra trạng thái sống (heartbeat / ping) của Server đối tác.
     * @return true nếu server còn hoạt động
     * @throws RemoteException nếu không thể liên lạc
     */
    boolean ping() throws RemoteException;

    /**
     * Yêu cầu lấy toàn bộ lịch sử tin nhắn để bù đắp dữ liệu khi một server vừa bật lên hoặc vừa tái kết nối.
     * @return Danh sách toàn bộ tin nhắn
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    List<ChatMessage> requestFullHistory() throws RemoteException;

    /**
     * Lấy danh sách client đang kết nối trực tiếp tại server này để hợp nhất danh sách online toàn cục.
     * @return Danh sách tên user
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    List<String> getLocalOnlineUsers() throws RemoteException;
}

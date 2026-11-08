package com.rmichat.interfaces;

import com.rmichat.common.ChatMessage;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface RMI Callback: Được Client triển khai và gửi tham chiếu Stub lên Server.
 * Server sẽ gọi các phương thức này để tự động đẩy (push) dữ liệu mới về Client
 * mà không cần Client phải dùng vòng lặp polling.
 */
public interface ChatClientCallback extends Remote {

    /**
     * Server gọi khi có tin nhắn mới (tin nhắn chat, người dùng vào/ra, thông báo hệ thống).
     * @param message Đối tượng tin nhắn
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    void onReceiveMessage(ChatMessage message) throws RemoteException;

    /**
     * Server gọi khi danh sách người dùng online có sự thay đổi.
     * @param onlineUsers Danh sách tên người dùng đang online trên toàn hệ thống
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    void onUserListUpdated(List<String> onlineUsers) throws RemoteException;

    /**
     * Server gọi khi có thông báo trạng thái đặc biệt từ Server.
     * @param notice Nội dung thông báo
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    void onServerNotification(String notice) throws RemoteException;
}

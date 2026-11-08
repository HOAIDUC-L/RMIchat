package com.rmichat.interfaces;

import com.rmichat.common.ChatMessage;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface RMI Server: Được triển khai bởi Chat Server.
 * Client dùng Stub của Interface này để tương tác với Server (đăng ký callback, gửi tin nhắn, lấy lịch sử...).
 */
public interface ChatServerInterface extends Remote {

    /**
     * Client gọi để đăng nhập và đăng ký RMI Callback với Server.
     * @param username Tên hiển thị của Client
     * @param callback Tham chiếu RMI Callback của Client
     * @return true nếu đăng nhập thành công, false nếu tên đã tồn tại
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    boolean registerClient(String username, ChatClientCallback callback) throws RemoteException;

    /**
     * Client gọi khi ngắt kết nối hoặc đóng ứng dụng để Server giải phóng Callback.
     * @param username Tên hiển thị của Client
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    void unregisterClient(String username) throws RemoteException;

    /**
     * Client gọi để gửi tin nhắn tới Server.
     * Server sẽ lưu tin, phát cho các client cục bộ và gọi đồng bộ sang Server đối tác.
     * @param message Đối tượng tin nhắn
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    void sendMessage(ChatMessage message) throws RemoteException;

    /**
     * Lấy danh sách toàn bộ người dùng đang online trên hệ thống.
     * @return Danh sách tên người dùng
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    List<String> getOnlineUsers() throws RemoteException;

    /**
     * Lấy toàn bộ lịch sử tin nhắn đã lưu trên Server.
     * @return Danh sách các tin nhắn đã gửi từ trước đến nay
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    List<ChatMessage> getHistory() throws RemoteException;

    /**
     * Kiểm tra xem Server này hiện có đang liên kết đồng bộ thành công với Server đối tác hay không.
     * @return true nếu đối tác đang online và đồng bộ bình thường
     * @throws RemoteException nếu xảy ra lỗi mạng RMI
     */
    boolean isPartnerServerConnected() throws RemoteException;
}

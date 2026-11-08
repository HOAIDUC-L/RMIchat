package com.rmichat.interfaces;

import com.rmichat.common.ChatGroup;
import com.rmichat.common.ChatMessage;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface RMI Server: Được triển khai bởi Chat Server.
 * Client dùng Stub của Interface này để tương tác với Server (đăng ký callback, gửi tin nhắn, quản lý nhóm, lấy lịch sử...).
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
     * Client gọi để gửi tin nhắn tới Server (hỗ trợ tin nhắn chung, riêng, nhóm, hình ảnh, tệp, âm thanh).
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

    // =========================================================================
    // QUẢN LÝ NHÓM CHAT (CUSTOM GROUP CHAT)
    // =========================================================================

    /**
     * Tạo một nhóm chat mới.
     * @param groupName Tên nhóm
     * @param creator Người tạo nhóm
     * @param initialMembers Danh sách thành viên ban đầu
     * @return Đối tượng ChatGroup vừa tạo
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    ChatGroup createGroup(String groupName, String creator, List<String> initialMembers) throws RemoteException;

    /**
     * Mời một thành viên mới vào nhóm chat.
     * @param groupId ID của nhóm
     * @param inviter Người thực hiện lời mời
     * @param targetUser Người được mời vào nhóm
     * @return true nếu mời thành công, false nếu người dùng đã ở trong nhóm hoặc nhóm không tồn tại
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    boolean inviteToGroup(String groupId, String inviter, String targetUser) throws RemoteException;

    /**
     * Rời khỏi nhóm chat.
     * @param groupId ID của nhóm
     * @param username Tên người dùng muốn rời nhóm
     * @return true nếu rời nhóm thành công
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    boolean leaveGroup(String groupId, String username) throws RemoteException;

    /**
     * Lấy danh sách các nhóm chat mà người dùng này đang tham gia.
     * @param username Tên người dùng
     * @return Danh sách các ChatGroup
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    List<ChatGroup> getGroupsForUser(String username) throws RemoteException;

    /**
     * Lấy thông tin chi tiết của một nhóm chat.
     * @param groupId ID của nhóm
     * @return Đối tượng ChatGroup hoặc null nếu không tồn tại
     * @throws RemoteException nếu xảy ra lỗi RMI
     */
    ChatGroup getGroup(String groupId) throws RemoteException;
}

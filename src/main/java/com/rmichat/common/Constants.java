package com.rmichat.common;

/**
 * Các hằng số cấu hình mặc định cho hệ thống RMI Chat.
 */
public final class Constants {
    private Constants() {}

    // Tên RMI Service đăng ký trong LocateRegistry
    public static final String CHAT_SERVICE_NAME = "ChatServerService";
    public static final String SYNC_SERVICE_NAME = "ServerSyncService";

    // Port mặc định cho RMI Registry
    public static final int DEFAULT_SERVER_1_PORT = 1099;
    public static final int DEFAULT_SERVER_2_PORT = 1100;

    // Tên mặc định của 2 Server
    public static final String SERVER_1_ID = "Server 1";
    public static final String SERVER_2_ID = "Server 2";
}

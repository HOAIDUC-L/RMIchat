package com.rmichat.server;

import com.rmichat.common.Constants;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;

/**
 * Điểm khởi chạy của Chat Server.
 * Hỗ trợ chạy qua tham số dòng lệnh hoặc nhập tương tác từ Console.
 * Tự động cấu hình java.rmi.server.hostname để đảm bảo hoạt động qua mạng LAN thật.
 */
public class ServerMain {

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("   HỆ THỐNG RMI CHAT PHÂN TÁN - ĐỒNG BỘ 2 SERVER");
        System.out.println("   Môn: Lập trình Mạng - Lab 5 (Java RMI Multi-Server)");
        System.out.println("============================================================\n");

        String serverId;
        int localPort;
        String partnerHost;
        int partnerPort;
        String localIp;

        if (args.length >= 4) {
            // Chạy với tham số dòng lệnh:
            // args[0]: serverId (1 hoặc 2 hoặc tên)
            // args[1]: localPort (ví dụ: 1099)
            // args[2]: partnerHost (ví dụ: 192.168.1.20)
            // args[3]: partnerPort (ví dụ: 1099 hoặc 1100)
            // args[4] (tùy chọn): localIp
            serverId = args[0].equalsIgnoreCase("1") ? Constants.SERVER_1_ID :
                    (args[0].equalsIgnoreCase("2") ? Constants.SERVER_2_ID : args[0]);
            localPort = Integer.parseInt(args[1]);
            partnerHost = args[2];
            partnerPort = Integer.parseInt(args[3]);
            localIp = args.length >= 5 ? args[4] : getLocalLanIp();
        } else {
            // Chế độ tương tác nhanh
            Scanner scanner = new Scanner(System.in);
            localIp = getLocalLanIp();

            System.out.println("Tìm thấy IP LAN của máy này: " + localIp);
            System.out.println("Chọn cấu hình khởi chạy:");
            System.out.println("  1. Khởi chạy [Server 1] (Cổng mặc định: 1099)");
            System.out.println("  2. Khởi chạy [Server 2] (Cổng mặc định: 1100)");
            System.out.println("  3. Tùy chỉnh nâng cao (Nhập tay toàn bộ thông số)");
            System.out.print("Lựa chọn của bạn (1/2/3) [Mặc định 1]: ");

            String choice = scanner.nextLine().trim();
            if (choice.isEmpty()) choice = "1";

            if ("1".equals(choice)) {
                serverId = Constants.SERVER_1_ID;
                localPort = Constants.DEFAULT_SERVER_1_PORT;
                System.out.print("Nhập IP máy chạy Server 2 [Mặc định: 127.0.0.1]: ");
                String pHost = scanner.nextLine().trim();
                partnerHost = pHost.isEmpty() ? "127.0.0.1" : pHost;
                System.out.print("Nhập Cổng Server 2 [Mặc định: 1100]: ");
                String pPortStr = scanner.nextLine().trim();
                partnerPort = pPortStr.isEmpty() ? Constants.DEFAULT_SERVER_2_PORT : Integer.parseInt(pPortStr);
            } else if ("2".equals(choice)) {
                serverId = Constants.SERVER_2_ID;
                localPort = Constants.DEFAULT_SERVER_2_PORT;
                System.out.print("Nhập IP máy chạy Server 1 [Mặc định: 127.0.0.1]: ");
                String pHost = scanner.nextLine().trim();
                partnerHost = pHost.isEmpty() ? "127.0.0.1" : pHost;
                System.out.print("Nhập Cổng Server 1 [Mặc định: 1099]: ");
                String pPortStr = scanner.nextLine().trim();
                partnerPort = pPortStr.isEmpty() ? Constants.DEFAULT_SERVER_1_PORT : Integer.parseInt(pPortStr);
            } else {
                System.out.print("Nhập tên Server (ví dụ: Server 1): ");
                serverId = scanner.nextLine().trim();
                System.out.print("Nhập cổng chạy Server này (ví dụ: 1099): ");
                localPort = Integer.parseInt(scanner.nextLine().trim());
                System.out.print("Nhập IP Server đối tác: ");
                partnerHost = scanner.nextLine().trim();
                System.out.print("Nhập cổng Server đối tác: ");
                partnerPort = Integer.parseInt(scanner.nextLine().trim());
            }

            System.out.print("Xác nhận IP máy này để quảng bá RMI (Enter để dùng " + localIp + "): ");
            String confirmIp = scanner.nextLine().trim();
            if (!confirmIp.isEmpty()) {
                localIp = confirmIp;
            }
        }

        startServer(serverId, localPort, partnerHost, partnerPort, localIp);
    }

    public static void startServer(String serverId, int localPort, String partnerHost, int partnerPort, String localIp) {
        try {
            // BƯỚC CỰC KỲ QUAN TRỌNG KHI CHẠY MẠNG LAN:
            // Chỉ định hostname công khai để RMI stub chứa đúng IP LAN của máy này,
            // tránh việc stub bị gán 127.0.0.1 khiến máy khác không thể kết nối hoặc gọi callback.
            System.setProperty("java.rmi.server.hostname", localIp);

            System.out.println("\n------------------------------------------------------------");
            System.out.printf("Đang khởi tạo %s...%n", serverId);
            System.out.printf(" - Địa chỉ IP máy (RMI Hostname): %s%n", localIp);
            System.out.printf(" - Cổng RMI Registry nội bộ     : %d%n", localPort);
            System.out.printf(" - Mục tiêu Server đối tác       : %s:%d%n", partnerHost, partnerPort);
            System.out.println("------------------------------------------------------------");

            // Tạo hoặc kết nối RMI Registry tại cổng chỉ định
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(localPort);
                System.out.println("[OK] Đã tạo mới RMI Registry tại cổng " + localPort);
            } catch (Exception e) {
                registry = LocateRegistry.getRegistry(localPort);
                System.out.println("[OK] Đã tìm thấy RMI Registry có sẵn tại cổng " + localPort);
            }

            // Khởi tạo đối tượng ChatServerImpl
            ChatServerImpl serverObj = new ChatServerImpl(serverId, localPort, partnerHost, partnerPort);

            // Đăng ký các dịch vụ lên Registry
            registry.rebind(Constants.CHAT_SERVICE_NAME, serverObj);
            registry.rebind(Constants.SYNC_SERVICE_NAME, serverObj);

            System.out.println("\n[THÀNH CÔNG] Dịch vụ RMI đã sẵn sàng:");
            System.out.printf(" -> Dành cho Client kết nối  : rmi://%s:%d/%s%n",
                    localIp, localPort, Constants.CHAT_SERVICE_NAME);
            System.out.printf(" -> Dành cho Server đồng bộ  : rmi://%s:%d/%s%n",
                    localIp, localPort, Constants.SYNC_SERVICE_NAME);
            System.out.println("\n[SẴN SÀNG] Đang lắng nghe kết nối từ Client và Server đối tác...\n");

            // Mở Trang Quản Trị Server (Admin GUI Dashboard) nếu có màn hình hiển thị
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try {
                        javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
                    } catch (Exception ignored) {}
                    ServerAdminGUI adminGUI = new ServerAdminGUI(serverObj);
                    adminGUI.setVisible(true);
                    System.out.println("[GUI] Đã mở Trang Quản Trị Server Admin GUI thành công.");
                });
            }

        } catch (Exception e) {
            System.err.println("[LỖI KHỞI ĐỘNG SERVER]: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Tự động dò tìm IP LAN (IPv4) của máy đang chạy.
     */
    public static String getLocalLanIp() {
        try {
            List<String> candidateIps = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // Ưu tiên dải mạng riêng tư thông dụng (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip;
                        }
                        candidateIps.add(ip);
                    }
                }
            }
            if (!candidateIps.isEmpty()) {
                return candidateIps.get(0);
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}

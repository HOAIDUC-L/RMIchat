# HỆ THỐNG CHAT ĐỒNG BỘ 2 SERVER SỬ DỤNG JAVA RMI (LAB 5 - LẬP TRÌNH MẠNG)

> **Môn học**: Lập trình Mạng  
> **Công nghệ**: Java 17 / 21 LTS, Java RMI (Remote Method Invocation), RMI Callback, Java Swing, SQLite JDBC  
> **Mô hình**: Kiến trúc P2P Server Sync (Đồng bộ ngang hàng giữa 2 Server), Multi-Client với cơ chế Server Push không dùng polling, Cơ sở dữ liệu SQLite phân tán, Giao diện Quản trị viên (Admin Dashboard GUI), và Chức năng Nhắn tin Riêng tư (Private Message / Whisper).

---

## 1. TỔNG QUAN KIẾN TRÚC & NGUYÊN LÝ HOẠT ĐỘNG

### 1.1. Sơ đồ kiến trúc mạng
```
           +-----------------------------------------------------------+
           |                       MẠNG LAN THẬT                       |
           |                   (Dải IP: 192.168.1.0/24)                |
           +-----------------------------+-----------------------------+
                                         |
         +-------------------------------+-------------------------------+
         |                                                               |
+---------------------------------+             +---------------------------------+
|          MÁY THẬT 1             |             |          MÁY THẬT 2             |
| IP: 192.168.1.10                |             | IP: 192.168.1.20                |
| Port RMI: 1099                  | <=========> | Port RMI: 1100                  |
| [SERVER 1]                      |  RMI SYNC   | [SERVER 2]                      |
| - SQLite: server1_chat.db       | (P2P Mesh)  | - SQLite: server2_chat.db       |
| - Admin Dashboard GUI (Swing)   |             | - Admin Dashboard GUI (Swing)   |
+----------------+----------------+             +----------------+----------------+
                 | (RMI Callback)                                | (RMI Callback)
        +--------+--------+                             +--------+--------+
        |                 |                             |                 |
+-------+-------+ +-------+-------+             +-------+-------+ +-------+-------+
|  MÁY ẢO / MÁY 3| |  MÁY ẢO / MÁY 4|             |  MÁY ẢO / MÁY 5| |  MÁY ẢO / MÁY 6|
| IP: 192.168.1.50| | IP: 192.168.1.51|             | IP: 192.168.1.60| | IP: 192.168.1.61|
| [CLIENT Alice] | | [CLIENT Charlie]|             | [CLIENT Bob]  | | [CLIENT David] |
+---------------+ +---------------+             +---------------+ +---------------+
```

---

### 1.2. Cơ chế chống vòng lặp vô hạn (Infinite Sync Loop Prevention)
Một trong những lỗi nghiêm trọng nhất của hệ thống đa server đồng bộ là **vòng lặp phản xạ vô tận (Infinite Echo Loop)**: Server 1 gửi sang Server 2, Server 2 tưởng tin mới lại gửi ngược về Server 1, dẫn đến tràn bộ nhớ (OOM) và sập mạng.

Hệ thống giải quyết triệt để vấn đề này bằng **cơ chế 3 lớp bảo vệ**:
1. **Định danh toàn cầu UUID**: Mỗi tin nhắn khi xuất phát từ Client được gán một mã `messageId = UUID.randomUUID().toString()` cùng trường `originServer`.
2. **Bộ nhớ đệm kiểm tra trùng lặp (Anti-loop Cache)**: Mỗi server duy trì tập hợp `processedMessageIds = ConcurrentHashMap.newKeySet()`. Khi nhận bất kỳ tin nhắn nào (cục bộ hoặc từ đối tác), Server kiểm tra nếu ID đã tồn tại thì lập tức dừng xử lý và loại bỏ.
3. **Phân tách kênh xử lý độc lập**:
   - Hàm `sendMessage(msg)`: Được gọi bởi Client trực tiếp $\rightarrow$ Server ghi nhận ID, lưu CSDL, đẩy về Client cục bộ, và gọi `partnerSyncStub.syncMessage(msg)` sang đối tác.
   - Hàm `syncMessage(msg)`: Được gọi bởi Server đối tác $\rightarrow$ Server ghi nhận ID, lưu CSDL, đẩy về Client cục bộ, và **TUYỆT ĐỐI KHÔNG** gọi lại hàm đồng bộ sang Server đối tác.

---

### 1.3. Cơ chế RMI Callback (Server Push - Không Polling)
- **Không dùng Polling**: Client không dùng bất kỳ vòng lặp `while(true)` hay `Thread.sleep` nào để hỏi server tin nhắn mới, giúp tiết kiệm 100% tài nguyên CPU và băng thông.
- **Server Push tức thì**:
  - Khi Client khởi chạy, nó export một đối tượng từ xa `ChatClientCallbackImpl` (kế thừa `UnicastRemoteObject`).
  - Khi bấm **Kết nối**, Client truyền tham chiếu Stub của Callback này lên Server qua `registerClient(username, callbackStub)`.
  - Khi có tin mới, người vào/ra phòng, hoặc nhận tin đồng bộ, Server duyệt danh sách Stub và gọi ngược lại:
    - `callback.onReceiveMessage(ChatMessage message)`
    - `callback.onUserListUpdated(List<String> onlineUsers)`
    - `callback.onServerNotification(String notice)`
  - Giao diện Swing của Client lập tức cập nhật trong mili-giây.

---

### 1.4. Cơ chế Nhắn tin Riêng tư Phân tán (Private Message / Whisper)
Hệ thống hỗ trợ nhắn tin riêng tư 1-1 giữa 2 người dùng bất kỳ, kể cả khi 2 người dùng kết nối vào **hai Server vật lý khác nhau**:
1. **Thao tác người dùng**:
   - Người dùng có thể chọn người nhận từ hộp chọn `Gửi tới: [🌐 Tất cả mọi người (Phòng chung)]` hoặc danh sách user online.
   - **Thao tác nhanh**: Nhấp đúp chuột (**Double Click**) vào bất kỳ tên người dùng nào trong danh sách "Người dùng Online" ở cột bên phải $\rightarrow$ Hệ thống tự động chuyển chế độ gửi riêng cho người đó!
2. **Định tuyến tin nhắn riêng (Message Routing)**:
   - Nếu Người nhận đang kết nối trên **cùng Server cục bộ**: Server chỉ đẩy tin nhắn cho Người gửi (để hiển thị bản sao) và Callback của Người nhận. Server không cần đồng bộ sang Server đối tác.
   - Nếu Người nhận đang kết nối trên **Server đối tác**: Server cục bộ gọi `partnerSyncStub.syncMessage(privateMsg)`. Server đối tác nhận được tin nhắn dạng `PRIVATE`, kiểm tra người nhận và chỉ đẩy duy nhất cho Callback của người đó.
3. **Bảo mật tuyệt đối**:
   - Người thứ 3 (ở cả Server 1 và Server 2) hoàn toàn **không thể nhận được hay nhìn thấy** tin nhắn riêng tư.
   - Bong bóng tin nhắn riêng được thiết kế với phong cách riêng biệt màu tím/magenta: `[🔒 RIÊNG TƯ -> @Bob]` hoặc `[🔒 RIÊNG TƯ TỪ @Alice]`.
4. **Kiểm tra trạng thái trực tuyến**: Nếu người nhận đã ngắt kết nối trước khi tin nhắn tới nơi, Server gửi thông báo hệ thống thông báo cho người gửi: `[HỆ THỐNG] Người dùng '{recipient}' không còn trực tuyến`.

---

### 1.5. Cơ sở dữ liệu SQLite nhúng (Persistence Layer)
- Mỗi Server sở hữu một file cơ sở dữ liệu vật lý riêng biệt:
  - Server 1: `database/server1_chat.db`
  - Server 2: `database/server2_chat.db`
- **Các bảng dữ liệu**:
  1. `messages`: Lưu trữ `message_id`, `sender`, `recipient` (null nếu chat chung), `content`, `origin_server`, `message_type`, `timestamp`.
  2. `user_activity`: Lưu nhật ký đăng nhập, đăng xuất, ngắt kết nối đột ngột của người dùng để phục vụ kiểm toán (Audit Log).
- **Tự động phục hồi**: Khi Server khởi động hoặc kết nối lại sau sự cố mất mạng, dữ liệu trong SQLite được nạp tự động vào bộ nhớ cache RAM, bảo đảm lịch sử trò chuyện không bao giờ bị mất.

---

## 2. CẤU TRÚC DỰ ÁN

```
RMIchat/
├── pom.xml                               # File cấu hình build Maven (Java 17/21, SQLite JDBC, JUnit 5)
├── README.md                             # Tài liệu hướng dẫn đồ án chi tiết & kịch bản demo
├── database/                             # Thư mục lưu file CSDL SQLite vật lý
│   ├── server1_chat.db                   # CSDL SQLite của Server 1
│   └── server2_chat.db                   # CSDL SQLite của Server 2
├── scripts/                              # Các kịch bản khởi chạy tự động
│   ├── compile.sh / compile.bat          # Biên dịch toàn bộ dự án ra file JAR
│   ├── run_server1_local.sh / .bat       # Chạy nhanh Server 1 trên máy cục bộ (localhost:1099)
│   ├── run_server2_local.sh / .bat       # Chạy nhanh Server 2 trên máy cục bộ (localhost:1100)
│   ├── run_server1.sh / run_server1.bat  # Chạy Server 1 qua mạng LAN thật (port 1099)
│   ├── run_server2.sh / run_server2.bat  # Chạy Server 2 qua mạng LAN thật (port 1100)
│   └── run_client.sh / run_client.bat    # Khởi chạy giao diện Client Swing
└── src/
    ├── main/java/com/rmichat/
    │   ├── common/
    │   │   ├── ChatMessage.java          # Model tin nhắn (UUID, sender, recipient, type, originServer)
    │   │   └── Constants.java            # Tên dịch vụ RMI, port mặc định
    │   ├── database/
    │   │   └── DatabaseManager.java      # Quản lý CSDL SQLite nhúng: CRUD tin nhắn & audit log
    │   ├── interfaces/
    │   │   ├── ChatServerInterface.java  # Remote Interface RMI: Client gọi Server
    │   │   ├── ChatClientCallback.java   # Remote Interface RMI: Server gọi Client Callback
    │   │   └── ServerSyncInterface.java  # Remote Interface RMI: Server 1 <-> Server 2 P2P Sync
    │   ├── server/
    │   │   ├── ChatServerImpl.java       # Cài đặt Server RMI, bộ định tuyến tin nhắn, chống lặp UUID
    │   │   ├── ServerAdminGUI.java       # Bảng điều khiển Quản trị Server trực quan (Swing)
    │   │   ├── ServerEventListener.java  # Observer Interface đẩy sự kiện real-time sang Admin GUI
    │   │   └── ServerMain.java           # Điểm chạy Server, tự động mở ServerAdminGUI
    │   └── client/
    │       ├── ChatClientCallbackImpl.java # Cài đặt Callback nhận tin nhắn từ Server
    │       ├── ChatClientGUI.java        # Giao diện Client Swing hiện đại, chat riêng, badge Server
    │       └── ClientMain.java           # Điểm chạy Client
    └── test/java/com/rmichat/test/
        └── MultiServerSyncTest.java      # Tự động kiểm thử: Đồng bộ 2 chiều, chống lặp, chat riêng tư
```

---

## 3. HƯỚNG DẪN CẤU HÌNH MẠNG & TƯỜNG LỬA (CHO BÀI THI MẠNG LAN)

### 3.1. Xác định địa chỉ IP LAN của các máy
- **Trên Windows**: Mở Command Prompt (`cmd`), gõ:
  ```cmd
  ipconfig
  ```
  Tìm dòng `IPv4 Address` của card Wi-Fi hoặc Ethernet (Ví dụ: `192.168.1.10`).
- **Trên Linux / macOS**: Mở Terminal, gõ:
  ```bash
  hostname -I
  # hoặc
  ip a
  ```

### 3.2. Cấu hình Máy ảo (VMware / VirtualBox)
> [!CAUTION]
> **LỖI PHỔ BIẾN NHẤT**: Để card mạng máy ảo ở chế độ **NAT**.  
> Khi dùng NAT, máy ảo chỉ có IP nội bộ ảo (ví dụ `192.168.122.x`), máy thật từ bên ngoài không thể truy cập vào máy ảo, dẫn tới **RMI Callback bị lỗi timeout/connection refused**!

**Cách khắc phục**:
1. Tắt máy ảo.
2. Mở **Settings** của máy ảo $\rightarrow$ Mục **Network Adapter**.
3. Chuyển sang chế độ **Bridged (Cầu nối)**:
   - *VMware*: Chọn **Bridged: Connected directly to the physical network**, tích chọn *"Replicate physical network connection state"*.
   - *VirtualBox*: Chọn **Bridged Adapter**, chọn đúng card mạng Wi-Fi hoặc dây mạng LAN đang kết nối.
4. Khởi động lại máy ảo, kiểm tra lại IP bằng `ipconfig` hoặc `ip a`. Lúc này máy ảo sẽ có IP cùng dải `192.168.1.x` với các máy thật.
5. Dùng lệnh `ping` giữa các máy để bảo đảm kết nối thông suốt 2 chiều:
   ```bash
   ping <IP_MÁY_ĐỐI_DIỆN>
   ```

### 3.3. Cấu hình Mở Cổng Tường Lửa (Firewall)
Hệ thống sử dụng cổng **1099** (Server 1) và **1100** (Server 2):

- **Trên Windows (Chạy Command Prompt bằng quyền Administrator)**:
  ```cmd
  netsh advfirewall firewall add rule name="Java RMI Port 1099" dir=in action=allow protocol=TCP localport=1099
  netsh advfirewall firewall add rule name="Java RMI Port 1100" dir=in action=allow protocol=TCP localport=1100
  ```
  *(Khi Windows hiện hộp thoại bảo mật Firewall cho Java, hãy bấm **Allow access** cho cả Private và Public Network).*

- **Trên Linux**:
  ```bash
  sudo ufw allow 1099/tcp
  sudo ufw allow 1100/tcp
  ```

### 3.4. Cấu hình Tham số RMI Hostname: `java.rmi.server.hostname`
Khi một Remote Object được export trong Java RMI, stub được gửi qua mạng sẽ mang địa chỉ IP định danh của máy đó. Nếu không đặt tham số này, Java sẽ mặc định dùng `127.0.0.1`, khiến máy khác nhận stub không thể gọi ngược lại qua LAN!
- `ServerMain` và `ChatClientGUI` đã được tích hợp thuật toán **tự động phát hiện IP LAN** và thiết lập:
  ```java
  System.setProperty("java.rmi.server.hostname", localIp);
  ```
- Trên màn hình Client Swing còn có ô nhập **"IP Máy này (Callback)"** cho phép xem và điều chỉnh nếu máy có nhiều card mạng.

---

## 4. HƯỚNG DẪN BIÊN DỊCH VÀ KHỞI CHẠY HỆ THỐNG

### 4.1. Biên dịch dự án thành 2 file JAR độc lập
Mở terminal/cmd tại thư mục dự án và chạy:
```bash
# Biên dịch và đóng gói Maven
mvn clean package
```
Sau khi hoàn tất, thư mục `target/` sẽ có:
- `rmichat-server.jar`: Dành cho Server 1 và Server 2 (đã kèm Admin Dashboard GUI và SQLite)
- `rmichat-client.jar`: Dành cho Client Swing

Hoặc bạn có thể dùng script có sẵn:
- **Linux/macOS**: `./scripts/compile.sh`
- **Windows**: `scripts\compile.bat`

---

### 4.2. Chạy trên 2 Máy Thật trong Mạng LAN (Mô hình Thực tế)

Giả định:
- **Máy Thật 1 (Server 1)**: IP `192.168.1.10`
- **Máy Thật 2 (Server 2)**: IP `192.168.1.20`
- **Các Máy Client / Máy Ảo**: Kết nối vào 1 trong 2 server trên

#### Bước 1: Khởi động Server 1 trên Máy Thật 1
```bash
# Trên Linux:
./scripts/run_server1.sh 192.168.1.20

# Trên Windows:
scripts\run_server1.bat 192.168.1.20
```
*(Cửa sổ **Server 1 Admin Dashboard** sẽ tự động mở lên).*

#### Bước 2: Khởi động Server 2 trên Máy Thật 2
```bash
# Trên Linux:
./scripts/run_server2.sh 192.168.1.10

# Trên Windows:
scripts\run_server2.bat 192.168.1.10
```
*(Cửa sổ **Server 2 Admin Dashboard** sẽ tự động mở lên).*

Ngay khi 2 Server tìm thấy nhau qua mạng LAN, console và Admin GUI của cả 2 Server sẽ chuyển sang trạng thái:
`🟢 ĐỐI TÁC ĐANG KẾT NỐI (ONLINE)`

#### Bước 3: Khởi động Client trên các máy
```bash
# Trên Linux:
./scripts/run_client.sh

# Trên Windows:
scripts\run_client.bat
```
- **Client Alice**:
  - Nhập Tên: `Alice`
  - Chọn: `Server 1 (Cổng 1099)`
  - Điền IP Server: `192.168.1.10`
  - Bấm **Kết nối**.
- **Client Bob**:
  - Nhập Tên: `Bob`
  - Chọn: `Server 2 (Cổng 1100)`
  - Điền IP Server: `192.168.1.20`
  - Bấm **Kết nối**.

---

### 4.3. Chạy thử nghiệm ngay trên 1 Máy (Local Simulation)
Để kiểm tra nhanh trên cùng một máy tính mà không cần 2 máy thật:
1. **Mở Terminal 1 (Server 1 - Cổng 1099)**:
   ```bash
   ./scripts/run_server1_local.sh
   # Hoặc Windows: scripts\run_server1_local.bat
   ```
2. **Mở Terminal 2 (Server 2 - Cổng 1100)**:
   ```bash
   ./scripts/run_server2_local.sh
   # Hoặc Windows: scripts\run_server2_local.bat
   ```
3. **Mở Terminal 3, 4, 5 (Chạy các Client khác nhau)**:
   ```bash
   ./scripts/run_client.sh
   # Hoặc: java -jar target/rmichat-client.jar
   ```
   - Client Alice: Kết nối vào Server 1 (localhost:1099).
   - Client Bob: Kết nối vào Server 2 (localhost:1100).
   - Client Charlie: Kết nối vào Server 1 (localhost:1099).

---

## 5. HƯỚNG DẪN SỬ DỤNG CÁC TÍNH NĂNG ĐẶC BIỆT

### 5.1. Chức năng Nhắn tin Riêng tư (Private Message / Whisper)
- **Cách gửi**:
  1. Trong ô `Gửi tới:`, nhấp vào và chọn tên người muốn gửi riêng (hoặc nhấp đúp vào tên người dùng trong bảng **Người dùng Online** bên phải).
  2. Nhập nội dung tin nhắn và bấm **Gửi** (hoặc ấn `Enter`).
- **Giao diện hiển thị**:
  - Người gửi: Bong bóng tin nhắn màu tím `[🔒 RIÊNG TƯ -> @Bob]: Tin mật nè Bob`.
  - Người nhận: Bong bóng tin nhắn màu tím `[🔒 RIÊNG TƯ TỪ @Alice]: Tin mật nè Bob`.
  - Các người dùng khác (Charlie, David...): **Hoàn toàn không nhận được gì**!
- **Quay lại chat chung**: Chọn lại mục `🌐 Tất cả mọi người (Phòng chung)` trong danh sách `Gửi tới:`.

### 5.2. Trang Quản trị Trực quan (Server Admin Dashboard)
Mỗi Server khi khởi động đều mở một cửa sổ quản trị viên gồm 4 thẻ tab:
1. **Tab 1: Người dùng Online & Kick**:
   - Hiển thị danh sách người dùng đang kết nối cục bộ và người dùng ở server đối tác.
   - Nút **"Kick Người Dùng"**: Cho phép Quản trị viên ngắt kết nối cưỡng chế bất kỳ ai đang vi phạm quy chế chat.
2. **Tab 2: Nhật ký hoạt động (Audit Logs)**:
   - Bảng nhật ký thời gian thực ghi lại: Đăng nhập (`JOIN`), Đăng xuất (`LEAVE`), Bị kick (`KICKED`), Chat chung (`CHAT`), Chat riêng (`PRIVATE`).
3. **Tab 3: Trạng thái Đồng bộ (Sync Monitor)**:
   - Theo dõi trạng thái liên kết P2P giữa 2 Server.
   - Giám sát bộ nhớ đệm chống vòng lặp (Anti-infinite Loop UUIDs cache).
4. **Tab 4: Cơ sở dữ liệu SQLite**:
   - Cho phép Quản trị viên tra cứu trực tiếp bảng tin nhắn trong file SQLite vật lý mà không cần mở công cụ ngoài.
   - Hiển thị đầy đủ cột `recipient` (người nhận tin riêng).
   - Nút **"Làm mới dữ liệu"** để cập nhật trạng thái mới nhất từ ổ cứng.
5. **Thanh Thông báo Toàn hệ thống (Admin Announcement)**:
   - Nằm ở dưới cùng của Admin Dashboard, cho phép Quản trị viên gõ thông báo và bấm **"Phát loa thông báo"** $\rightarrow$ Thông báo màu đỏ sẽ được phát tới tất cả các Client ở cả 2 Server cùng lúc.
   - Nút **"Kích hoạt Đồng bộ Lại (Force Sync)"** để ép 2 server rà soát lại dữ liệu.

---

## 6. KỊCH BẢN TRÌNH BÀY DEMO ĐẠT ĐIỂM TỐI ĐA (10/10) VỚI GIẢNG VIÊN

Khi báo cáo đồ án với thầy cô, bạn hãy thực hiện theo đúng 6 bước chuẩn mực sau:

| Bước | Nội dung Demo | Thao tác thực hiện | Điểm nhấn kỹ thuật cần giải thích |
|---|---|---|---|
| **1** | **Minh chứng độc lập & LAN thật** | Mở `ipconfig` trên Máy 1 (`192.168.1.10`) và Máy 2 (`192.168.1.20`). Khởi động 2 Server. | Chỉ cho thầy cô thấy log `java.rmi.server.hostname` và dòng `[KẾT NỐI ĐỐI TÁC THÀNH CÔNG]`. Hai Server chạy độc lập không phụ thuộc trung gian. |
| **2** | **Demo RMI Callback (Server Push)** | Kết nối Client Alice vào Server 1, Client Bob vào Server 2. | Nhấn mạnh: Client **không dùng vòng lặp polling** (`while(true)` hay `Thread.sleep`). Client export `UnicastRemoteObject` và Server gọi trực tiếp Callback khi có sự kiện. |
| **3** | **Demo Đồng bộ 2 chiều & Nhãn Server** | Alice gửi *"Xin chào từ Server 1"*, Bob thấy ngay lập tức kèm nhãn `[Server 1]`. Bob trả lời lại, Alice nhận kèm nhãn `[Server 2]`. | Tin nhắn đồng bộ qua mạng LAN tức thì. Danh sách Online cập nhật đầy đủ người ở cả 2 Server. |
| **4** | **Demo Nhắn tin Riêng tư Xuyên Server** | Alice nhấp đúp vào `Bob` trên danh sách Online và gửi tin mật. | Bob nhận được tin nhắn riêng màu tím `[🔒 RIÊNG TƯ TỪ @Alice]`. Client thứ 3 (Charlie) không hề nhận được. Chứng minh cơ chế định tuyến tin riêng phân tán. |
| **5** | **Minh chứng Chống vòng lặp vô hạn** | Mở file `ChatServerImpl.java` và xem Console 2 Server. | Giải thích: Sử dụng `ConcurrentHashMap` lưu UUID tin nhắn (`processedMessageIds`). Hàm `syncMessage` chỉ phân phối cho Client mà **không gọi ngược lại** sang server đối tác. |
| **6** | **Demo Admin Dashboard & Chịu lỗi (Fault Tolerance)** | - Thử bấm **Kick** một user trên Admin GUI.<br>- Phát thông báo toàn hệ thống.<br>- Xem bảng SQLite.<br>- Tắt đột ngột 1 Server rồi bật lại. | - Server tự động bắt `RemoteException` dọn dẹp Client.<br>- Khi Server 2 bật lại, cơ chế Auto-Reconnect tự động kết nối và đồng bộ lại toàn bộ dữ liệu. |

---

## 7. CHẠY KIỂM THỬ TỰ ĐỘNG (AUTOMATED UNIT TESTS)

Dự án đã tích hợp sẵn kịch bản kiểm thử tự động toàn diện trong `MultiServerSyncTest.java`:
```bash
mvn test
```
Kiểm thử sẽ tự động thực hiện:
- Khởi tạo 2 Server RMI trên 2 cổng ảo độc lập.
- Kết nối 3 Mock Client (Alice ở Server 1, Bob ở Server 2, Charlie ở Server 1).
- Kiểm tra tính đúng đắn của đồng bộ 2 chiều.
- Kiểm tra khả năng chống lặp vô hạn (số lượng tin nhắn không bị duplicate).
- Kiểm tra nhắn tin riêng tư xuyên server (Bob nhận được, Charlie bị chặn không nhận được).
- Kiểm tra nhắn tin riêng tư nội bộ cùng server (Alice nhắn Charlie, Bob không nhận được).
- Kiểm tra ngắt kết nối an toàn (Graceful disconnect).

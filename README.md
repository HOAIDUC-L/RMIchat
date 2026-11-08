# HỆ THỐNG CHAT ĐỒNG BỘ 2 SERVER SỬ DỤNG JAVA RMI (LAB 5 - LẬP TRÌNH MẠNG)

> **Môn học**: Lập trình Mạng  
> **Công nghệ**: Java 17 / 21 LTS, Java RMI (Remote Method Invocation), RMI Callback, Java Swing, SQLite JDBC, Java Sound API (`javax.sound.sampled`)  
> **Mô hình**: Kiến trúc P2P Server Sync (Đồng bộ ngang hàng giữa 2 Server), Multi-Client với cơ chế Server Push không dùng polling, Cơ sở dữ liệu SQLite phân tán, Giao diện Quản trị viên (Admin Dashboard GUI), Chức năng Nhắn tin Riêng tư (Private Message), **Gửi Đa Phương Tiện (Ảnh, File, Audio/Thu âm)** và **Hệ thống Nhóm Chat Tùy biến (Group Chat)**.

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
| - File Uploads: uploads_server1 |             | - File Uploads: uploads_server2 |
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
2. **Bộ nhớ đệm kiểm tra trùng lặp (Anti-loop Cache)**: Mỗi server duy trì tập hợp `processedMessageIds = ConcurrentHashMap.newKeySet()`. Khi nhận bất kỳ tin nhắn nào (cục bộ hoặc từ đối tác), Server kiểm tra nếu ID đã tồn tại thì lập tức dừng xử lý và loại bỏ (`return`).
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
    - `callback.onGroupListUpdated(List<ChatGroup> userGroups)`
    - `callback.onServerNotification(String notice)`
  - Giao diện Swing của Client lập tức cập nhật trong mili-giây.

---

### 1.4. Cơ chế Nhắn tin Riêng tư Phân tán (Private Message / Whisper)
- Hỗ trợ nhắn tin riêng 1-1 giữa 2 người dùng bất kỳ kể cả ở 2 Server khác nhau.
- **Định tuyến tin riêng**:
  - Người nhận ở cùng Server: Server phân phối ngay cho người gửi và người nhận, không gửi sang Server đối tác.
  - Người nhận ở Server đối tác: Server gọi `syncMessage` sang đối tác; Server đối tác nhận được chỉ đẩy duy nhất cho Callback của người nhận, **không broadcast cho bất kỳ ai khác**.
  - Người thứ 3 tuyệt đối không nhận được gói tin này.

---

### 1.5. Chức năng Gửi Đa Phương Tiện (Ảnh, File, Audio/Ghi âm thoại)
Hệ thống cho phép gửi dữ liệu nhị phân đính kèm trong tin nhắn qua Java RMI:
1. **Gửi Hình ảnh (Image)**:
   - Hỗ trợ các định dạng: `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.webp`.
   - Client tự động sinh ảnh thu nhỏ (**Thumbnail**) hiển thị trực quan ngay trong bong bóng chat.
   - Người nhận có thể bấm vào ảnh để mở cửa sổ phóng to độ nét cao (**High-res Preview**) hoặc bấm nút **Lưu ảnh về máy**.
2. **Gửi Tệp tin bất kỳ (File)**:
   - Hỗ trợ tài liệu `.pdf`, `.docx`, `.zip`, `.txt`... kèm dung lượng hiển thị đẹp mắt (KB, MB).
   - Có nút **[💾 Tải xuống]** trực tiếp từ khung chat để lưu tệp vào bất kỳ thư mục nào trên máy tính.
3. **Tin nhắn Thoại / Audio (Voice Message)**:
   - Tích hợp sẵn bộ thu âm Microphone trực tiếp bằng Java Sound API (`javax.sound.sampled`).
   - Âm thanh được đóng gói chuẩn **WAV 16kHz Mono** trong trẻo, dung lượng cực nhẹ (~32 KB/giây).
   - Có hộp thoại thu âm: Bắt đầu ghi, Dừng, Nghe thử, và Gửi.
   - Người nhận có thể bấm **[▶ Phát âm thanh]** hoặc **[⏹ Dừng]** ngay trên giao diện chat.
4. **Giới hạn an toàn**: Áp dụng giới hạn tối đa **10 MB / tệp** để bảo vệ bộ nhớ heap của JVM qua mạng LAN.

---

### 1.6. Hệ thống Nhóm Chat Tùy biến (Custom Group Chat)
1. **Tạo nhóm linh hoạt**: Bất kỳ người dùng nào cũng có thể bấm **"➕ Tạo Nhóm Mới"**, nhập tên nhóm và tích chọn mời các bạn bè trực tuyến cùng tham gia.
2. **Đồng bộ P2P xuyên Server**: Khi Alice (Server 1) tạo nhóm và mời Bob (Server 2):
   - Server 1 gọi `partnerSyncStub.syncGroupCreate(group)` sang Server 2.
   - Server 2 cập nhật CSDL và gọi RMI Callback cập nhật danh sách nhóm cho Bob ngay lập tức.
3. **Mời thêm thành viên**: Thành viên trong nhóm có thể bấm **"➕ Mời Vào Nhóm"** để thêm người dùng mới.
4. **Bảo mật & Cô lập Tin nhắn Nhóm (Group Isolation)**:
   - Tin nhắn trong nhóm chỉ được gửi cho các thành viên của nhóm đó (ở cả Server 1 và Server 2).
   - Người không thuộc nhóm **tuyệt đối không nhận được tin nhắn** (đã được xác thực qua Unit Test tự động).
   - Tin nhắn nhóm được đánh dấu huy hiệu màu xanh ngọc: `[👥 Nhóm: Tên Nhóm]`.

---

### 1.7. Cơ sở dữ liệu SQLite nhúng (Persistence Layer)
- Mỗi Server sở hữu một file cơ sở dữ liệu vật lý riêng biệt:
  - Server 1: `database/server1_chat.db` & thư mục lưu tệp `database/uploads_server1/`
  - Server 2: `database/server2_chat.db` & thư mục lưu tệp `database/uploads_server2/`
- **Các bảng dữ liệu**:
  1. `messages`: Lưu trữ tin nhắn, người nhận riêng, nhóm chat, đường dẫn tệp đính kèm.
  2. `chat_groups`: Lưu danh sách nhóm chat (`group_id`, `group_name`, `creator`, `created_at`).
  3. `group_members`: Lưu danh sách thành viên trong từng nhóm (`group_id`, `username`, `joined_at`).
  4. `user_activity`: Lưu nhật ký đăng nhập, đăng xuất, bị kick để phục vụ kiểm toán (Audit Log).
- **Tự động phục hồi**: Khi Server khởi động hoặc tái kết nối sau sự cố mất mạng, dữ liệu trong SQLite được nạp tự động vào bộ nhớ RAM.

---

## 2. CẤU TRÚC DỰ ÁN

```
RMIchat/
├── pom.xml                               # File cấu hình build Maven (Java 17/21, SQLite JDBC, JUnit 5)
├── README.md                             # Tài liệu hướng dẫn đồ án chi tiết & kịch bản demo
├── database/                             # Thư mục lưu file CSDL SQLite vật lý và tệp tải lên
│   ├── server1_chat.db                   # CSDL SQLite của Server 1
│   ├── server2_chat.db                   # CSDL SQLite của Server 2
│   ├── uploads_server1/                  # Tệp đa phương tiện lưu tại Server 1
│   └── uploads_server2/                  # Tệp đa phương tiện lưu tại Server 2
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
    │   │   ├── ChatGroup.java            # [MỚI] Model Nhóm chat (groupId, groupName, creator, members)
    │   │   ├── ChatMessage.java          # Model tin nhắn (UUID, sender, recipient, group, media data, type)
    │   │   └── Constants.java            # Tên dịch vụ RMI, port mặc định
    │   ├── database/
    │   │   └── DatabaseManager.java      # Quản lý CSDL SQLite nhúng: CRUD tin nhắn, nhóm chat & audit log
    │   ├── interfaces/
    │   │   ├── ChatServerInterface.java  # Remote Interface RMI: Client gọi Server (hỗ trợ nhóm & media)
    │   │   ├── ChatClientCallback.java   # Remote Interface RMI: Server gọi Client Callback (nhận tin & nhóm)
    │   │   └── ServerSyncInterface.java  # Remote Interface RMI: Server 1 <-> Server 2 P2P Sync (sync group & media)
    │   ├── server/
    │   │   ├── ChatServerImpl.java       # Cài đặt Server RMI, bộ định tuyến tin nhắn nhóm/riêng/media, chống lặp
    │   │   ├── ServerAdminGUI.java       # Bảng điều khiển Quản trị Server trực quan (Swing)
    │   │   ├── ServerEventListener.java  # Observer Interface đẩy sự kiện real-time sang Admin Dashboard
    │   │   └── ServerMain.java           # Điểm chạy Server, tự động mở ServerAdminGUI
    │   └── client/
    │       ├── AudioRecorderDialog.java  # [MỚI] Cửa sổ thu âm giọng nói trực tiếp qua Micro & nghe thử
    │       ├── ImagePreviewDialog.java   # [MỚI] Cửa sổ xem trước ảnh kích thước lớn & tải ảnh
    │       ├── ChatClientCallbackImpl.java # Cài đặt Callback nhận tin nhắn và cập nhật nhóm từ Server
    │       ├── ChatClientGUI.java        # Giao diện Client Swing: Tab nhóm, nút gửi Ảnh/File/Audio, thumbnail
    │       └── ClientMain.java           # Điểm chạy Client
    └── test/java/com/rmichat/test/
        └── MultiServerSyncTest.java      # Tự động kiểm thử: Đồng bộ 2 chiều, chống lặp, chat riêng, gửi file, nhóm chat
```

---

## 3. HƯỚNG DẪN CẤU HÌNH MẠNG & TƯỜNG LỬA (CHO BÀI THI MẠNG LAN)

### 3.1. Xác định địa chỉ IP LAN của các máy
- **Trên Windows**: Mở Command Prompt (`cmd`), gõ: `ipconfig` (Tìm dòng `IPv4 Address`, ví dụ: `192.168.1.10`).
- **Trên Linux / macOS**: Mở Terminal, gõ: `hostname -I` hoặc `ip a`.

### 3.2. Cấu hình Máy ảo (VMware / VirtualBox)
> [!CAUTION]
> Chuyển card mạng máy ảo từ **NAT** sang **Bridged Adapter** để máy ảo cùng lớp mạng `192.168.1.x` với máy thật, đảm bảo RMI Callback hoạt động thông suốt 2 chiều.

### 3.3. Mở Cổng Tường Lửa (Firewall)
- **Trên Windows (cmd quyền Administrator)**:
  ```cmd
  netsh advfirewall firewall add rule name="Java RMI Port 1099" dir=in action=allow protocol=TCP localport=1099
  netsh advfirewall firewall add rule name="Java RMI Port 1100" dir=in action=allow protocol=TCP localport=1100
  ```
- **Trên Linux**:
  ```bash
  sudo ufw allow 1099/tcp
  sudo ufw allow 1100/tcp
  ```

---

## 4. HƯỚNG DẪN BIÊN DỊCH VÀ KHỞI CHẠY HỆ THỐNG

### 4.1. Biên dịch dự án thành 2 file JAR độc lập
```bash
mvn clean package
```
Sau khi build xong, trong thư mục `target/` sẽ có:
- `target/rmichat-server.jar`: Dành cho Server 1 và Server 2
- `target/rmichat-client.jar`: Dành cho Client Swing

---

### 4.2. Chạy trên 2 Máy Thật trong Mạng LAN

- **Máy 1 (Server 1 - IP 192.168.1.10)**:
  ```bash
  ./scripts/run_server1.sh 192.168.1.20
  ```
- **Máy 2 (Server 2 - IP 192.168.1.20)**:
  ```bash
  ./scripts/run_server2.sh 192.168.1.10
  ```
- **Các Máy Client**:
  ```bash
  ./scripts/run_client.sh
  ```

---

### 4.3. Chạy thử nghiệm trên 1 Máy (Local Simulation)
1. **Terminal 1**: `./scripts/run_server1_local.sh`
2. **Terminal 2**: `./scripts/run_server2_local.sh`
3. **Terminal 3, 4, 5**: Mở 3 cửa sổ Client `./scripts/run_client.sh` kết nối vào Server 1 (cổng 1099) và Server 2 (cổng 1100).

---

## 5. HƯỚNG DẪN SỬ DỤNG CÁC TÍNH NĂNG ĐẶC BIỆT

### 5.1. Gửi Hình ảnh, Tệp tin, Âm thanh / Thu âm
1. **Gửi Ảnh (`🖼️ Gửi Ảnh`)**: Chọn ảnh từ máy tính $\rightarrow$ Nhập chú thích $\rightarrow$ Ảnh hiển thị dưới dạng Thumbnail đẹp mắt trong khung chat $\rightarrow$ Nhấn vào ảnh để xem phóng to hoặc bấm `[💾 Lưu ảnh]`.
2. **Gửi Tệp tin (`📎 Gửi Tệp`)**: Chọn file bất kỳ (PDF, Word, Zip...) $\rightarrow$ Khung chat hiển thị icon file kèm dung lượng $\rightarrow$ Bấm `[💾 Nhấn vào đây để tải tệp xuống]`.
3. **Ghi âm Thoại (`🎙️ Thoại / Audio`)**: Mở hộp thoại ghi âm $\rightarrow$ Bấm `🔴 Bắt đầu Ghi` $\rightarrow$ Nói vào Micro $\rightarrow$ Bấm `⏹ Dừng` $\rightarrow$ Bấm `▶ Nghe thử` $\rightarrow$ Bấm `📤 Gửi âm thanh` $\rightarrow$ Người nhận bấm `[▶ Phát âm thanh]` để nghe giọng nói trực tiếp.

### 5.2. Quản lý Nhóm Chat (Custom Group Chat)
1. Ở cột bên phải, chuyển sang thẻ **"👥 Nhóm Chat"**.
2. **Tạo Nhóm**: Bấm **"➕ Tạo Nhóm Mới..."** $\rightarrow$ Nhập tên nhóm và chọn các thành viên trực tuyến $\rightarrow$ Nhóm được tạo và đồng bộ sang Server đối tác ngay lập tức.
3. **Trò chuyện trong nhóm**:
   - Nhấp đúp vào tên nhóm trong danh sách $\rightarrow$ Hộp chọn `Gửi tới:` tự động chuyển sang nhóm đó.
   - Tin nhắn, ảnh, file gửi vào nhóm có huy hiệu `[👥 Nhóm: Tên Nhóm]` màu xanh ngọc.
   - **Chỉ thành viên trong nhóm mới nhận được tin**, người ngoài nhóm hoàn toàn không thấy.
4. **Mời thêm bạn**: Chọn nhóm $\rightarrow$ Bấm **"➕ Mời Vào Nhóm..."** $\rightarrow$ Chọn người cần mời.
5. **Rời nhóm**: Chọn nhóm $\rightarrow$ Bấm **"🚪 Rời Nhóm Này"**.

---

## 6. KỊCH BẢN TRÌNH BÀY DEMO ĐẠT ĐIỂM TỐI ĐA (10/10) VỚI GIẢNG VIÊN

| Bước | Nội dung Demo | Thao tác thực hiện | Điểm nhấn kỹ thuật cần giải thích |
|---|---|---|---|
| **1** | **Minh chứng độc lập & LAN thật** | Mở `ipconfig` trên Máy 1 (`192.168.1.10`) và Máy 2 (`192.168.1.20`). Khởi động 2 Server. | Chỉ cho thầy cô thấy log `java.rmi.server.hostname` và dòng `[KẾT NỐI ĐỐI TÁC THÀNH CÔNG]`. Hai Server chạy độc lập không phụ thuộc trung gian. |
| **2** | **Demo RMI Callback (Server Push)** | Kết nối Client Alice vào Server 1, Client Bob vào Server 2. | Nhấn mạnh: Client **không dùng vòng lặp polling** (`while(true)` hay `Thread.sleep`). Client export `UnicastRemoteObject` và Server gọi trực tiếp Callback khi có sự kiện. |
| **3** | **Demo Gửi Ảnh & Tệp tin xuyên Server** | Alice gửi 1 bức ảnh và 1 file PDF. | Bob trên Server 2 thấy ảnh hiển thị Thumbnail trực quan và có nút tải về máy. Minh chứng khả năng truyền tải dữ liệu nhị phân qua RMI. |
| **4** | **Demo Thu âm Giọng nói qua Micro** | Alice mở hộp thoại ghi âm, nói 3 giây và gửi. | Bob bấm nút `[▶ Phát âm thanh]` nghe rõ giọng nói của Alice thông qua thư viện chuẩn Java Sound API. |
| **5** | **Demo Nhóm Chat & Phân quyền Cô lập** | Alice tạo "Nhóm Đồ Án" gồm Alice và Bob. Alice gửi tin vào nhóm. | Bob (thuộc nhóm) nhận được tin. Client thứ 3 (Charlie) không thuộc nhóm **tuyệt đối không nhận được gì**. Minh chứng định tuyến nhóm phân tán. |
| **6** | **Minh chứng Chống vòng lặp vô hạn** | Mở file `ChatServerImpl.java` và xem Console 2 Server. | Giải thích: Sử dụng `ConcurrentHashMap` lưu UUID tin nhắn (`processedMessageIds`). Hàm `syncMessage` chỉ phân phối cho Client mà **không gọi ngược lại** sang server đối tác. |
| **7** | **Demo Admin Dashboard & Chịu lỗi** | Thử kick user, phát thông báo Admin, xem SQLite. Tắt 1 Server rồi bật lại. | Server tự bắt `RemoteException` dọn dẹp. Khi Server 2 bật lại, cơ chế Auto-Reconnect tự động kết nối và đồng bộ lại lịch sử tin nhắn và nhóm chat. |

---

## 7. CHẠY KIỂM THỬ TỰ ĐỘNG (AUTOMATED UNIT TESTS)

```bash
mvn test
```
Kiểm thử tự động kiểm tra toàn bộ:
- Đồng bộ 2 chiều & Chống lặp vô hạn UUID.
- Nhắn tin riêng tư (Whisper) và chặn rò rỉ tin nhắn cho bên thứ 3.
- **Truyền nhận hình ảnh & tệp tin nhị phân nguyên vẹn xuyên Server**.
- **Tạo nhóm chat, đồng bộ danh sách nhóm xuyên Server**.
- **Cô lập tin nhắn nhóm: thành viên nhận được, người ngoài nhóm bị chặn hoàn toàn**.
- Mời thành viên mới xuyên Server và ngắt kết nối an toàn.

package com.rmichat.test;

import com.rmichat.common.ChatMessage;
import com.rmichat.common.Constants;
import com.rmichat.interfaces.ChatClientCallback;
import com.rmichat.interfaces.ChatServerInterface;
import com.rmichat.server.ChatServerImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class MultiServerSyncTest {

    private static final int PORT_S1 = 12099;
    private static final int PORT_S2 = 12100;

    private static Registry registry1;
    private static Registry registry2;
    private static ChatServerImpl server1;
    private static ChatServerImpl server2;

    @BeforeAll
    public static void setUp() throws Exception {
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        System.setProperty("rmichat.db.dir", "target/test-db-" + System.currentTimeMillis());

        // 1. Tạo Registry cho Server 1 và Server 2
        registry1 = LocateRegistry.createRegistry(PORT_S1);
        registry2 = LocateRegistry.createRegistry(PORT_S2);

        // 2. Khởi tạo 2 Server
        server1 = new ChatServerImpl("Server 1", PORT_S1, "127.0.0.1", PORT_S2);
        server2 = new ChatServerImpl("Server 2", PORT_S2, "127.0.0.1", PORT_S1);

        // 3. Đăng ký services
        registry1.rebind(Constants.CHAT_SERVICE_NAME, server1);
        registry1.rebind(Constants.SYNC_SERVICE_NAME, server1);

        registry2.rebind(Constants.CHAT_SERVICE_NAME, server2);
        registry2.rebind(Constants.SYNC_SERVICE_NAME, server2);

        // Chờ 2 giây để luồng Monitor kết nối 2 Server với nhau
        Thread.sleep(2000);
    }

    @AfterAll
    public static void tearDown() {
        try {
            UnicastRemoteObject.unexportObject(server1, true);
            UnicastRemoteObject.unexportObject(server2, true);
            UnicastRemoteObject.unexportObject(registry1, true);
            UnicastRemoteObject.unexportObject(registry2, true);
        } catch (Exception ignored) {}
    }

    static class MockClientCallback extends UnicastRemoteObject implements ChatClientCallback {
        private final String name;
        final List<ChatMessage> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final List<List<String>> userListUpdates = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch messageLatch;

        MockClientCallback(String name) throws Exception {
            super();
            this.name = name;
        }

        void setMessageLatch(CountDownLatch latch) {
            this.messageLatch = latch;
        }

        @Override
        public void onReceiveMessage(ChatMessage message) {
            receivedMessages.add(message);
            if (messageLatch != null) {
                messageLatch.countDown();
            }
        }

        @Override
        public void onUserListUpdated(List<String> onlineUsers) {
            userListUpdates.add(new ArrayList<>(onlineUsers));
        }

        @Override
        public void onServerNotification(String notice) {
            // No-op for test
        }
    }

    @Test
    public void testTwoWaySyncAndAntiLoop() throws Exception {
        // 1. Kết nối Mock Client 1 vào Server 1
        MockClientCallback client1 = new MockClientCallback("Alice");
        ChatServerInterface s1Stub = (ChatServerInterface) registry1.lookup(Constants.CHAT_SERVICE_NAME);
        boolean reg1 = s1Stub.registerClient("Alice", client1);
        assertTrue(reg1, "Alice phải đăng ký thành công vào Server 1");

        // 2. Kết nối Mock Client 2 vào Server 2
        MockClientCallback client2 = new MockClientCallback("Bob");
        ChatServerInterface s2Stub = (ChatServerInterface) registry2.lookup(Constants.CHAT_SERVICE_NAME);
        boolean reg2 = s2Stub.registerClient("Bob", client2);
        assertTrue(reg2, "Bob phải đăng ký thành công vào Server 2");

        // Chờ đồng bộ trạng thái User giữa 2 Server
        Thread.sleep(1500);

        // Kiểm tra danh sách online: Cả 2 server đều phải thấy cả Alice và Bob
        List<String> s1Users = s1Stub.getOnlineUsers();
        List<String> s2Users = s2Stub.getOnlineUsers();
        assertTrue(s1Users.contains("Alice") && s1Users.contains("Bob"), "Server 1 phải thấy cả 2 user");
        assertTrue(s2Users.contains("Alice") && s2Users.contains("Bob"), "Server 2 phải thấy cả 2 user");

        // 3. Test Đồng bộ Chiều 1: Alice gửi tin nhắn tới Server 1
        CountDownLatch latch1 = new CountDownLatch(1);
        client2.setMessageLatch(latch1);

        ChatMessage msgFromAlice = ChatMessage.createChat("Alice", "Xin chào từ Server 1!", "Server 1");
        s1Stub.sendMessage(msgFromAlice);

        // Bob ở Server 2 phải nhận được tin nhắn trong vòng 3 giây
        boolean bobReceived = latch1.await(3, TimeUnit.SECONDS);
        assertTrue(bobReceived, "Bob trên Server 2 phải nhận được tin nhắn do Alice gửi tại Server 1");

        // Kiểm tra nội dung tin nhắn Bob nhận được
        ChatMessage bobLastMsg = client2.receivedMessages.get(client2.receivedMessages.size() - 1);
        assertEquals("Xin chào từ Server 1!", bobLastMsg.getContent());
        assertEquals("Alice", bobLastMsg.getSender());
        assertEquals("Server 1", bobLastMsg.getOriginServer());

        // 4. Test Đồng bộ Chiều 2: Bob gửi tin nhắn trả lời tới Server 2
        CountDownLatch latch2 = new CountDownLatch(1);
        client1.setMessageLatch(latch2);

        ChatMessage msgFromBob = ChatMessage.createChat("Bob", "Chào Alice, tôi ở Server 2!", "Server 2");
        s2Stub.sendMessage(msgFromBob);

        // Alice ở Server 1 phải nhận được tin nhắn trong vòng 3 giây
        boolean aliceReceived = latch2.await(3, TimeUnit.SECONDS);
        assertTrue(aliceReceived, "Alice trên Server 1 phải nhận được tin nhắn do Bob gửi tại Server 2");

        ChatMessage aliceLastMsg = client1.receivedMessages.get(client1.receivedMessages.size() - 1);
        assertEquals("Chào Alice, tôi ở Server 2!", aliceLastMsg.getContent());
        assertEquals("Bob", aliceLastMsg.getSender());
        assertEquals("Server 2", aliceLastMsg.getOriginServer());

        // 5. KIỂM TRA CHỐNG LẶP VÔ HẠN (ANTI-INFINITE LOOP):
        // Cả 2 Server chỉ có đúng 2 tin nhắn chat (ngoài các tin JOIN), không bị duplicate liên tục!
        List<ChatMessage> s1History = s1Stub.getHistory();
        List<ChatMessage> s2History = s2Stub.getHistory();

        long s1ChatCount = s1History.stream().filter(m -> m.getType() == ChatMessage.MessageType.CHAT).count();
        long s2ChatCount = s2History.stream().filter(m -> m.getType() == ChatMessage.MessageType.CHAT).count();

        assertEquals(2, s1ChatCount, "Server 1 chỉ được chứa đúng 2 tin nhắn CHAT (không bị nhân đôi)");
        assertEquals(2, s2ChatCount, "Server 2 chỉ được chứa đúng 2 tin nhắn CHAT (không bị nhân đôi)");

        // 6. TEST NHẮN TIN RIÊNG TƯ (PRIVATE MESSAGING / WHISPER):
        // Thêm Charlie vào Server 1 để kiểm tra tính riêng tư (không người thứ 3 nào nhận được tin riêng)
        MockClientCallback client3 = new MockClientCallback("Charlie");
        boolean reg3 = s1Stub.registerClient("Charlie", client3);
        assertTrue(reg3, "Charlie phải đăng ký thành công vào Server 1");
        Thread.sleep(1000);

        // a) Alice (Server 1) gửi tin nhắn riêng cho Bob (Server 2)
        CountDownLatch latchPrivateBob = new CountDownLatch(1);
        CountDownLatch latchPrivateAlice = new CountDownLatch(1);
        client2.setMessageLatch(latchPrivateBob);
        client1.setMessageLatch(latchPrivateAlice);

        ChatMessage whisperToBob = ChatMessage.createPrivate("Alice", "Bob", "Tin mật riêng tư từ Alice cho Bob", "Server 1");
        s1Stub.sendMessage(whisperToBob);

        // Bob trên Server 2 phải nhận được
        boolean bobGotWhisper = latchPrivateBob.await(3, TimeUnit.SECONDS);
        assertTrue(bobGotWhisper, "Bob trên Server 2 phải nhận được tin nhắn riêng từ Alice");
        ChatMessage bobPrivateMsg = client2.receivedMessages.get(client2.receivedMessages.size() - 1);
        assertEquals("Tin mật riêng tư từ Alice cho Bob", bobPrivateMsg.getContent());
        assertTrue(bobPrivateMsg.isPrivate());
        assertEquals("Bob", bobPrivateMsg.getRecipient());
        assertEquals("Alice", bobPrivateMsg.getSender());

        // Alice (người gửi) cũng nhận được tin nhắn để hiển thị trên giao diện của mình
        boolean aliceGotWhisper = latchPrivateAlice.await(3, TimeUnit.SECONDS);
        assertTrue(aliceGotWhisper, "Alice (người gửi) cũng phải nhận được bản tin riêng");

        // Charlie (người thứ 3 trên Server 1) TUYỆT ĐỐI KHÔNG nhận được tin nhắn riêng này!
        boolean charlieLeaked = client3.receivedMessages.stream()
                .anyMatch(m -> "Tin mật riêng tư từ Alice cho Bob".equals(m.getContent()));
        assertFalse(charlieLeaked, "Charlie không được nhận tin riêng giữa Alice và Bob!");

        // b) Bob (Server 2) phản hồi tin nhắn riêng cho Alice (Server 1)
        CountDownLatch latchReplyAlice = new CountDownLatch(1);
        client1.setMessageLatch(latchReplyAlice);

        ChatMessage replyToAlice = ChatMessage.createPrivate("Bob", "Alice", "Đã nhận tin mật từ bạn", "Server 2");
        s2Stub.sendMessage(replyToAlice);

        boolean aliceGotReply = latchReplyAlice.await(3, TimeUnit.SECONDS);
        assertTrue(aliceGotReply, "Alice trên Server 1 phải nhận được phản hồi riêng từ Bob");

        // Charlie vẫn KHÔNG nhận được phản hồi riêng này!
        boolean charlieLeakedReply = client3.receivedMessages.stream()
                .anyMatch(m -> "Đã nhận tin mật từ bạn".equals(m.getContent()));
        assertFalse(charlieLeakedReply, "Charlie không được nhận tin phản hồi riêng giữa Bob và Alice!");

        // c) Thử gửi tin nhắn riêng trên cùng 1 server: Alice (Server 1) nhắn riêng cho Charlie (Server 1)
        CountDownLatch latchPrivateCharlie = new CountDownLatch(1);
        client3.setMessageLatch(latchPrivateCharlie);

        ChatMessage localWhisper = ChatMessage.createPrivate("Alice", "Charlie", "Chào Charlie trên cùng Server 1", "Server 1");
        s1Stub.sendMessage(localWhisper);

        boolean charlieGotLocal = latchPrivateCharlie.await(3, TimeUnit.SECONDS);
        assertTrue(charlieGotLocal, "Charlie phải nhận được tin nhắn riêng nội bộ Server 1");

        // Bob trên Server 2 TUYỆT ĐỐI KHÔNG nhận được tin riêng nội bộ giữa Alice và Charlie
        boolean bobLeakedLocal = client2.receivedMessages.stream()
                .anyMatch(m -> "Chào Charlie trên cùng Server 1".equals(m.getContent()));
        assertFalse(bobLeakedLocal, "Bob (Server 2) không được nhận tin riêng nội bộ Server 1!");

        // 7. Test Ngắt kết nối (Clean unregister)
        s1Stub.unregisterClient("Charlie");
        s1Stub.unregisterClient("Alice");
        Thread.sleep(1000);
        assertFalse(s2Stub.getOnlineUsers().contains("Alice"), "Server 2 phải cập nhật Alice đã offline");

        s2Stub.unregisterClient("Bob");
    }
}

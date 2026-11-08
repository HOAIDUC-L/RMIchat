package com.rmichat.client;

import javax.swing.*;

/**
 * Điểm khởi chạy của Client.
 */
public class ClientMain {
    public static void main(String[] args) {
        // Thiết lập giao diện theo Look & Feel mặc định của hệ điều hành
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            ChatClientGUI clientGUI = new ChatClientGUI();
            clientGUI.setVisible(true);
        });
    }
}

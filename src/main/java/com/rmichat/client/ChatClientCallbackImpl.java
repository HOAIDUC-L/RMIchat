package com.rmichat.client;

import com.rmichat.common.ChatMessage;
import com.rmichat.interfaces.ChatClientCallback;

import javax.swing.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

/**
 * Cài đặt ChatClientCallback phía Client.
 * Nhận lệnh RMI từ Server và chuyển tiếp sự kiện về giao diện Swing trên Event Dispatch Thread (EDT).
 */
public class ChatClientCallbackImpl extends UnicastRemoteObject implements ChatClientCallback {
    private static final long serialVersionUID = 1L;

    public interface ClientCallbackListener {
        void onMessageReceived(ChatMessage message);
        void onUserListChanged(List<String> users);
        void onNotification(String notice);
    }

    private final transient ClientCallbackListener listener;

    public ChatClientCallbackImpl(ClientCallbackListener listener) throws RemoteException {
        super();
        this.listener = listener;
    }

    public ChatClientCallbackImpl(int exportPort, ClientCallbackListener listener) throws RemoteException {
        super(exportPort);
        this.listener = listener;
    }

    @Override
    public void onReceiveMessage(ChatMessage message) throws RemoteException {
        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.onMessageReceived(message));
        }
    }

    @Override
    public void onUserListUpdated(List<String> onlineUsers) throws RemoteException {
        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.onUserListChanged(onlineUsers));
        }
    }

    @Override
    public void onServerNotification(String notice) throws RemoteException {
        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.onNotification(notice));
        }
    }
}

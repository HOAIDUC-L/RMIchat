package com.rmichat.client;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;

/**
 * Hộp thoại phóng to và xem hình ảnh với độ phân giải đầy đủ,
 * hỗ trợ lưu ảnh về ổ cứng máy tính.
 */
public class ImagePreviewDialog extends JDialog {

    public ImagePreviewDialog(Frame owner, byte[] imageBytes, String imageName) {
        super(owner, "🖼️ Xem ảnh: " + (imageName != null ? imageName : "Hình ảnh"), true);
        initUI(imageBytes, imageName);
    }

    private void initUI(byte[] imageBytes, String imageName) {
        setSize(750, 600);
        setLocationRelativeTo(getOwner());

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel imgLabel = new JLabel("Đang tải ảnh...", SwingConstants.CENTER);

        if (imageBytes != null && imageBytes.length > 0) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
                if (img != null) {
                    imgLabel.setText(null);
                    imgLabel.setIcon(new ImageIcon(img));
                } else {
                    imgLabel.setText("Không thể giải mã dữ liệu hình ảnh.");
                }
            } catch (Exception e) {
                imgLabel.setText("Lỗi nạp ảnh: " + e.getMessage());
            }
        } else {
            imgLabel.setText("Dữ liệu hình ảnh trống.");
        }

        JScrollPane scrollPane = new JScrollPane(imgLabel);
        scrollPane.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Thanh công cụ bên dưới
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));

        JButton btnSave = new JButton("💾 Lưu ảnh về máy (Save As...)");
        btnSave.setBackground(new Color(13, 110, 253));
        btnSave.setForeground(Color.WHITE);
        btnSave.setFocusPainted(false);
        btnSave.addActionListener(e -> saveImageToDisk(imageBytes, imageName));

        JButton btnClose = new JButton("Đóng");
        btnClose.addActionListener(e -> dispose());

        bottomPanel.add(btnSave);
        bottomPanel.add(btnClose);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void saveImageToDisk(byte[] imageBytes, String defaultName) {
        if (imageBytes == null || imageBytes.length == 0) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Lưu hình ảnh về máy");
        fileChooser.setSelectedFile(new File(defaultName != null ? defaultName : "image.png"));

        int result = fileChooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File destFile = fileChooser.getSelectedFile();
            try {
                Files.write(destFile.toPath(), imageBytes);
                JOptionPane.showMessageDialog(this,
                        "Lưu ảnh thành công tại:\n" + destFile.getAbsolutePath(),
                        "Thành công", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Lỗi khi lưu ảnh: " + ex.getMessage(),
                        "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}

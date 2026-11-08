package com.rmichat.client;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;

/**
 * Hộp thoại ghi âm giọng nói trực tiếp qua Micro hoặc chọn file âm thanh có sẵn từ máy tính.
 * Sử dụng thư viện chuẩn của Java SE (javax.sound.sampled) đóng gói định dạng WAV chuẩn 16kHz Mono.
 */
public class AudioRecorderDialog extends JDialog {

    public interface AudioCallback {
        void onAudioReady(byte[] audioData, String fileName, String caption);
    }

    private final AudioCallback callback;
    private TargetDataLine targetLine;
    private ByteArrayOutputStream recordBuffer;
    private Thread recordThread;
    private volatile boolean isRecording = false;

    private byte[] recordedWavBytes = null;
    private String chosenFileName = "voice_message.wav";
    private Clip previewClip;

    private JLabel lblStatus;
    private JLabel lblTimer;
    private JButton btnRecord;
    private JButton btnStop;
    private JButton btnPlayPreview;
    private JButton btnChooseFile;
    private JButton btnSend;
    private JTextField txtCaption;
    private Timer timer;
    private int secondsElapsed = 0;

    public AudioRecorderDialog(Frame owner, AudioCallback callback) {
        super(owner, "🎙️ Ghi âm Tin nhắn Thoại / Gửi Audio", true);
        this.callback = callback;
        initUI();
    }

    private void initUI() {
        setSize(480, 320);
        setLocationRelativeTo(getOwner());
        setResizable(false);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(new Color(248, 249, 250));

        // 1. TOP PANEL: Trạng thái & Đồng hồ
        JPanel topPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        topPanel.setOpaque(false);

        lblStatus = new JLabel("Nhấn 'Bắt đầu Ghi' để thu âm qua Micro", SwingConstants.CENTER);
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lblStatus.setForeground(new Color(33, 37, 41));

        lblTimer = new JLabel("00:00", SwingConstants.CENTER);
        lblTimer.setFont(new Font("Segoe UI", Font.BOLD, 28));
        lblTimer.setForeground(new Color(220, 53, 69));

        topPanel.add(lblStatus);
        topPanel.add(lblTimer);
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // 2. CENTER PANEL: Caption & Chọn file
        JPanel centerPanel = new JPanel(new GridBagLayout());
        centerPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        centerPanel.add(new JLabel("Lời nhắn kèm theo:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtCaption = new JTextField("Tin nhắn thoại", 20);
        centerPanel.add(txtCaption, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
        btnChooseFile = new JButton("📁 Hoặc chọn file âm thanh có sẵn từ máy (.wav, .mp3)...");
        btnChooseFile.addActionListener(e -> chooseLocalAudioFile());
        centerPanel.add(btnChooseFile, gbc);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // 3. BOTTOM PANEL: Nút điều khiển
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        btnPanel.setOpaque(false);

        btnRecord = new JButton("🔴 Bắt đầu Ghi");
        btnRecord.setBackground(new Color(220, 53, 69));
        btnRecord.setForeground(Color.WHITE);
        btnRecord.setFocusPainted(false);
        btnRecord.addActionListener(e -> startRecording());

        btnStop = new JButton("⏹ Dừng");
        btnStop.setEnabled(false);
        btnStop.addActionListener(e -> stopRecording());

        btnPlayPreview = new JButton("▶ Nghe thử");
        btnPlayPreview.setEnabled(false);
        btnPlayPreview.addActionListener(e -> playPreview());

        btnSend = new JButton("📤 Gửi âm thanh");
        btnSend.setBackground(new Color(40, 167, 69));
        btnSend.setForeground(Color.WHITE);
        btnSend.setEnabled(false);
        btnSend.setFocusPainted(false);
        btnSend.addActionListener(e -> sendAudio());

        JButton btnCancel = new JButton("Đóng");
        btnCancel.addActionListener(e -> {
            stopPreview();
            dispose();
        });

        btnPanel.add(btnRecord);
        btnPanel.add(btnStop);
        btnPanel.add(btnPlayPreview);
        btnPanel.add(btnSend);
        btnPanel.add(btnCancel);

        mainPanel.add(btnPanel, BorderLayout.SOUTH);
        setContentPane(mainPanel);

        // Timer đếm giây khi thu âm
        timer = new Timer(1000, e -> {
            secondsElapsed++;
            int min = secondsElapsed / 60;
            int sec = secondsElapsed % 60;
            lblTimer.setText(String.format("%02d:%02d", min, sec));
        });
    }

    private AudioFormat getAudioFormat() {
        // 16kHz, 16 bit, Mono, Signed, Little-Endian
        return new AudioFormat(16000.0f, 16, 1, true, false);
    }

    private void startRecording() {
        try {
            stopPreview();
            AudioFormat format = getAudioFormat();
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                JOptionPane.showMessageDialog(this, "Hệ thống không tìm thấy Microphone hỗ trợ định dạng âm thanh này.",
                        "Lỗi Micro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            targetLine = (TargetDataLine) AudioSystem.getLine(info);
            targetLine.open(format);
            targetLine.start();

            recordBuffer = new ByteArrayOutputStream();
            isRecording = true;
            secondsElapsed = 0;
            lblTimer.setText("00:00");
            timer.start();

            recordThread = new Thread(() -> {
                byte[] buffer = new byte[2048];
                while (isRecording) {
                    int bytesRead = targetLine.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        recordBuffer.write(buffer, 0, bytesRead);
                    }
                }
            });
            recordThread.start();

            lblStatus.setText("🎙️ Đang thu âm... Nói vào Micro của bạn");
            lblStatus.setForeground(new Color(220, 53, 69));
            btnRecord.setEnabled(false);
            btnStop.setEnabled(true);
            btnPlayPreview.setEnabled(false);
            btnSend.setEnabled(false);
            btnChooseFile.setEnabled(false);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Không thể kích hoạt Micro: " + e.getMessage(),
                    "Lỗi Micro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        timer.stop();

        try {
            if (targetLine != null) {
                targetLine.stop();
                targetLine.close();
            }
            if (recordThread != null) {
                recordThread.join(1000);
            }
        } catch (Exception ignored) {}

        byte[] pcmData = recordBuffer != null ? recordBuffer.toByteArray() : new byte[0];
        if (pcmData.length > 0) {
            recordedWavBytes = addWavHeader(pcmData, getAudioFormat());
            chosenFileName = "voice_" + System.currentTimeMillis() + ".wav";

            lblStatus.setText(String.format("✅ Đã ghi âm xong (%d giây - %.1f KB)",
                    secondsElapsed, recordedWavBytes.length / 1024.0));
            lblStatus.setForeground(new Color(40, 167, 69));

            btnRecord.setEnabled(true);
            btnStop.setEnabled(false);
            btnPlayPreview.setEnabled(true);
            btnSend.setEnabled(true);
            btnChooseFile.setEnabled(true);
        } else {
            lblStatus.setText("Không thu được tín hiệu âm thanh nào.");
            btnRecord.setEnabled(true);
            btnStop.setEnabled(false);
        }
    }

    private void playPreview() {
        if (recordedWavBytes == null || recordedWavBytes.length == 0) return;
        try {
            stopPreview();
            ByteArrayInputStream bais = new ByteArrayInputStream(recordedWavBytes);
            AudioInputStream ais = AudioSystem.getAudioInputStream(bais);
            previewClip = AudioSystem.getClip();
            previewClip.open(ais);
            previewClip.start();
            btnPlayPreview.setText("⏹ Dừng nghe");
            previewClip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    SwingUtilities.invokeLater(() -> btnPlayPreview.setText("▶ Nghe thử"));
                }
            });
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Lỗi phát âm thanh: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopPreview() {
        if (previewClip != null && previewClip.isRunning()) {
            previewClip.stop();
            previewClip.close();
        }
        btnPlayPreview.setText("▶ Nghe thử");
    }

    private void chooseLocalAudioFile() {
        stopPreview();
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Chọn tệp âm thanh");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Tệp âm thanh (*.wav, *.mp3, *.ogg, *.m4a)", "wav", "mp3", "ogg", "m4a", "aac"));

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            if (selectedFile.length() > 10 * 1024 * 1024) {
                JOptionPane.showMessageDialog(this, "Dung lượng tệp âm thanh vượt quá 10MB!", "Tệp quá lớn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                recordedWavBytes = Files.readAllBytes(selectedFile.toPath());
                chosenFileName = selectedFile.getName();
                lblStatus.setText(String.format("✅ Đã nạp tệp: %s (%.1f KB)", chosenFileName, recordedWavBytes.length / 1024.0));
                lblStatus.setForeground(new Color(13, 110, 253));
                btnPlayPreview.setEnabled(chosenFileName.toLowerCase().endsWith(".wav"));
                btnSend.setEnabled(true);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Lỗi đọc tệp: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void sendAudio() {
        stopPreview();
        if (recordedWavBytes != null && recordedWavBytes.length > 0) {
            String caption = txtCaption.getText().trim();
            if (caption.isEmpty()) caption = chosenFileName;
            callback.onAudioReady(recordedWavBytes, chosenFileName, caption);
            dispose();
        }
    }

    /**
     * Bọc dữ liệu thô PCM thành file WAV chuẩn có 44-byte RIFF header.
     */
    private byte[] addWavHeader(byte[] pcmData, AudioFormat format) {
        int totalDataLen = pcmData.length + 36;
        int sampleRate = (int) format.getSampleRate();
        int channels = format.getChannels();
        int byteRate = sampleRate * channels * (format.getSampleSizeInBits() / 8);

        byte[] header = new byte[44];
        // RIFF/WAVE header
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        // 'fmt ' chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // 16 for PCM
        header[20] = 1; header[21] = 0; // PCM format
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * (format.getSampleSizeInBits() / 8)); // block align
        header[33] = 0;
        header[34] = (byte) format.getSampleSizeInBits(); // bits per sample
        header[35] = 0;
        // 'data' chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff);
        header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff);
        header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(header);
            out.write(pcmData);
        } catch (IOException ignored) {}
        return out.toByteArray();
    }
}

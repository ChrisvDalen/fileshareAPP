package client;

import javax.swing.*;
import java.awt.*;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

public class FileSyncClient {
    private static String SERVER_ADDRESS;
    private static int PORT;
    private static String CLIENT_FOLDER;
    private static JTextArea logArea;
    private static JProgressBar progressBar;
    private static DefaultListModel<String> fileListModel;
    private static ExecutorService executorService = Executors.newFixedThreadPool(2);
    private static boolean cancelUpload = false;
    private static final String LOG_FILE = "client_log.txt";

    public static void main(String[] args) {
        loadConfig();
        setupGUI();
        startAutoSync();
    }

    private static void setupGUI() {
        JFrame frame = new JFrame("File Sync Client");
        frame.setSize(500, 400);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout());
        logArea = new JTextArea();
        logArea.setEditable(false);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.SOUTH);

        JButton uploadButton = new JButton("Select File to Upload");
        uploadButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setMultiSelectionEnabled(true);
            int returnValue = fileChooser.showOpenDialog(null);
            if (returnValue == JFileChooser.APPROVE_OPTION) {
                File[] selectedFiles = fileChooser.getSelectedFiles();
                for (File file : selectedFiles) {
                    executorService.submit(() -> sendFile(file));
                }
            }
        });
        panel.add(uploadButton, BorderLayout.NORTH);

        JButton cancelButton = new JButton("Cancel Upload");
        cancelButton.addActionListener(e -> cancelUpload = true);
        panel.add(cancelButton, BorderLayout.EAST);

        frame.add(panel);
        frame.setVisible(true);
    }

    private static void sendFile(File file) {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try (Socket socket = new Socket(SERVER_ADDRESS, PORT);
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket.getInputStream());
                 FileInputStream fis = new FileInputStream(file)) {

                if (cancelUpload) {
                    log("Upload canceled for: " + file.getName());
                    cancelUpload = false;
                    return;
                }

                dos.writeUTF(file.getName());
                dos.writeLong(file.length());
                dos.writeLong(file.lastModified());
                dos.writeUTF(getFileChecksum(file));

                boolean shouldSend = dis.readBoolean();
                if (shouldSend) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalRead = 0;

                    progressBar.setValue(0);
                    progressBar.setMaximum((int) file.length());

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        if (cancelUpload) {
                            log("Upload canceled during transfer: " + file.getName());
                            return;
                        }
                        dos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        progressBar.setValue((int) totalRead);
                    }
                    log("File sent: " + file.getName());
                    fileListModel.addElement("Uploaded: " + file.getName());
                    return;
                } else {
                    log("File already exists on server: " + file.getName());
                    return;
                }
            } catch (IOException e) {
                attempt++;
                log("Error sending file (attempt " + attempt + "): " + file.getName() + " - " + e.getMessage());
                if (attempt >= maxRetries) {
                    log("Max retries reached. Failed to upload: " + file.getName());
                }
            }
        }
    }

    private static void startAutoSync() {
        Thread autoSyncThread = new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Path path = Paths.get(CLIENT_FOLDER);
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

                log("Auto-sync started. Monitoring folder: " + CLIENT_FOLDER);

                while (true) {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path filePath = ((WatchEvent<Path>) event).context();
                        File file = new File(CLIENT_FOLDER, filePath.toString());

                        log("Detected file change: " + file.getName());
                        executorService.submit(() -> sendFile(file));
                    }
                    key.reset();
                }
            } catch (Exception e) {
                log("Auto-sync error: " + e.getMessage());
            }
        });
        autoSyncThread.setDaemon(true);
        autoSyncThread.start();
    }

    private static void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
        System.out.println(message);

        try (FileWriter fw = new FileWriter(LOG_FILE, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter pw = new PrintWriter(bw)) {
            pw.println(message);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    private static String getFileChecksum(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] byteArray = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesRead);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Checksum calculation error: " + e.getMessage());
        }
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream("client_config.properties")) {
            properties.load(fis);
            SERVER_ADDRESS = properties.getProperty("server_address", "localhost");
            PORT = Integer.parseInt(properties.getProperty("port", "5000"));
            CLIENT_FOLDER = properties.getProperty("client_folder", "client_folder");
        } catch (IOException e) {
            SERVER_ADDRESS = "localhost";
            PORT = 5000;
            CLIENT_FOLDER = "client_folder";
        }
    }
}

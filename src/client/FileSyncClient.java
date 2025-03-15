package client;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileSyncClient {
    private static String SERVER_ADDRESS;
    private static int PORT;
    private static String CLIENT_FOLDER;
    private static JTextArea logArea;
    private static JProgressBar progressBar;
    private static DefaultListModel<String> fileListModel;
    private static ExecutorService executorService = Executors.newFixedThreadPool(2);

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
        uploadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setMultiSelectionEnabled(true);
                int returnValue = fileChooser.showOpenDialog(null);
                if (returnValue == JFileChooser.APPROVE_OPTION) {
                    File[] selectedFiles = fileChooser.getSelectedFiles();
                    for (File file : selectedFiles) {
                        executorService.submit(() -> sendFile(file));
                    }
                }
            }
        });
        panel.add(uploadButton, BorderLayout.NORTH);

        JList<String> fileList = new JList<>(fileListModel = new DefaultListModel<>());
        JScrollPane fileScrollPane = new JScrollPane(fileList);
        panel.add(fileScrollPane, BorderLayout.WEST);

        fileList.setDropTarget(new DropTarget() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> droppedFiles = (List<File>) dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : droppedFiles) {
                        executorService.submit(() -> sendFile(file));
                    }
                } catch (Exception ex) {
                    log("Drag & Drop failed: " + ex.getMessage());
                }
            }
        });

        frame.add(panel);
        frame.setVisible(true);
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
                        WatchEvent.Kind<?> kind = event.kind();
                        Path filePath = ((WatchEvent<Path>) event).context();
                        File file = new File(CLIENT_FOLDER, filePath.toString());

                        if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            log("Detected file change: " + file.getName());
                            executorService.submit(() -> sendFile(file));
                        }
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

    private static void sendFile(File file) {
        try (Socket socket = new Socket(SERVER_ADDRESS, PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(file)) {

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
                    dos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                    progressBar.setValue((int) totalRead);
                }
                log("File sent: " + file.getName());
                fileListModel.addElement("Uploaded: " + file.getName());
            } else {
                log("File already exists on server: " + file.getName());
            }
        } catch (IOException e) {
            log("Error sending file: " + e.getMessage());
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

    private static void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
        System.out.println(message);
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
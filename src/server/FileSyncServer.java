package server;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.Properties;
import java.util.concurrent.*;

public class FileSyncServer {
    private static int PORT;
    private static String STORAGE_DIR;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);
    private static JTextArea logArea;

    public static void main(String[] args) {
        loadConfig();
        new File(STORAGE_DIR).mkdir(); // Ensure storage directory exists
        setupGUI();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            log("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                log("New client connected: " + clientSocket.getInetAddress());
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            log("Server error: " + e.getMessage());
        }
    }

    static class ClientHandler implements Runnable {
        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

                String fileName = dis.readUTF();
                long fileSize = dis.readLong();
                long lastModified = dis.readLong();

                File file = new File(STORAGE_DIR, fileName);
                if (file.exists() && file.length() == fileSize && file.lastModified() == lastModified) {
                    dos.writeBoolean(false); // No need to resend
                } else {
                    dos.writeBoolean(true); // Send file
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = dis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                    file.setLastModified(lastModified);
                    log("Received file: " + fileName);
                }
            } catch (IOException e) {
                log("Client error: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    log("Socket close error: " + e.getMessage());
                }
            }
        }
    }

    private static void setupGUI() {
        JFrame frame = new JFrame("File Sync Server");
        frame.setSize(400, 300);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        logArea = new JTextArea();
        logArea.setEditable(false);
        frame.add(new JScrollPane(logArea), BorderLayout.CENTER);

        frame.setVisible(true);
    }

    private static void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
        System.out.println(message);
    }

    private static void loadConfig() {
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream("server_config.properties")) {
            properties.load(fis);
            PORT = Integer.parseInt(properties.getProperty("port", "5000"));
            STORAGE_DIR = properties.getProperty("storage_dir", "server_storage");
        } catch (IOException e) {
            PORT = 5000;
            STORAGE_DIR = "server_storage";
        }
    }
}

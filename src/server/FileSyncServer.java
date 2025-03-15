package src.server;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.concurrent.*;

public class FileSyncServer {
    private static final int PORT = 5000;
    private static final String STORAGE_DIR = "/var/lib/file_storage/"; // Store files in Docker volume
    private static Connection connection;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public static void main(String[] args) {
        setupDatabase();
        new File(STORAGE_DIR).mkdirs(); // Ensure the directory exists

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());
                threadPool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void setupDatabase() {
        try {
            Properties props = new Properties();
            props.setProperty("user", "user");
            props.setProperty("password", "pass");
            connection = DriverManager.getConnection("jdbc:postgresql://localhost:5433/filesync", props);
            System.out.println("Connected to database.");

            String createTableSQL = """
                CREATE TABLE IF NOT EXISTS file_metadata (
                    id SERIAL PRIMARY KEY,
                    file_name TEXT UNIQUE NOT NULL,
                    file_size BIGINT NOT NULL,
                    last_modified TIMESTAMP NOT NULL,
                    checksum TEXT NOT NULL,
                    file_path TEXT NOT NULL
                )
            """;
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(createTableSQL);
            }
        } catch (SQLException e) {
            e.printStackTrace();
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
                String checksum = dis.readUTF();

                // Ensure correct Linux-compatible path
                File file = new File(STORAGE_DIR, fileName);
                String filePath = file.getPath().replace("\\", "/"); // Ensure path is Linux-compatible

                if (file.exists() && file.length() == fileSize && getFileChecksum(file).equals(checksum)) {
                    dos.writeBoolean(false);
                } else {
                    dos.writeBoolean(true);
                    saveFile(file, dis, fileSize);
                    saveToDatabase(fileName, fileSize, lastModified, checksum, filePath);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void saveFile(File file, DataInputStream dis, long fileSize) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalRead = 0;

                while (totalRead < fileSize && (bytesRead = dis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
            }
        }

        private void saveToDatabase(String fileName, long fileSize, long lastModified, String checksum, String filePath) throws SQLException {
            String sql = "INSERT INTO file_metadata (file_name, file_size, last_modified, checksum, file_path) " +
                    "VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT (file_name) DO UPDATE SET file_size = EXCLUDED.file_size, " +
                    "last_modified = EXCLUDED.last_modified, checksum = EXCLUDED.checksum, file_path = EXCLUDED.file_path";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, fileName);
                pstmt.setLong(2, fileSize);
                pstmt.setTimestamp(3, new Timestamp(lastModified));
                pstmt.setString(4, checksum);
                pstmt.setString(5, filePath);
                pstmt.executeUpdate();
                System.out.println("Saved to DB & Stored in Volume: " + fileName);
            }
        }

        private String getFileChecksum(File file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] byteArray = new byte[1024];
                int bytesRead;
                while ((bytesRead = fis.read(byteArray)) != -1) {
                    digest.update(byteArray, 0, bytesRead);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}

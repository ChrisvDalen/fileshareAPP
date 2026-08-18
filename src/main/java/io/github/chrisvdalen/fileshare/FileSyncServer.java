package io.github.chrisvdalen.fileshare;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;

public final class FileSyncServer {
    private static final JTextArea LOG_AREA = new JTextArea();

    private FileSyncServer() {
    }

    public static void main(String[] args) throws IOException {
        var config = loadConfig();
        Files.createDirectories(config.storageDirectory());
        setupGui();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var serverSocket = new ServerSocket(config.port())) {
            log("Server started on port " + config.port());
            while (!Thread.currentThread().isInterrupted()) {
                var client = serverSocket.accept();
                executor.submit(() -> handle(client, config.storageDirectory()));
            }
        }
    }

    private static void handle(Socket socket, Path storageDirectory) {
        try (socket;
             var input = new DataInputStream(socket.getInputStream());
             var output = new DataOutputStream(socket.getOutputStream())) {
            var fileName = input.readUTF();
            var fileSize = input.readLong();
            var lastModified = input.readLong();
            var target = FileReceiver.safeTarget(storageDirectory, fileName);

            var unchanged = Files.exists(target)
                    && Files.size(target) == fileSize
                    && Files.getLastModifiedTime(target).toMillis() == lastModified;
            output.writeBoolean(!unchanged);
            output.flush();

            if (!unchanged) {
                FileReceiver.receive(input, target, fileSize, lastModified);
                log("Received file: " + fileName);
            }
        } catch (IOException exception) {
            log("Client error: " + exception.getMessage());
        }
    }

    private static void setupGui() {
        SwingUtilities.invokeLater(() -> {
            var frame = new JFrame("File Sync Server");
            frame.setSize(500, 320);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            LOG_AREA.setEditable(false);
            frame.add(new JScrollPane(LOG_AREA), BorderLayout.CENTER);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }

    private static void log(String message) {
        SwingUtilities.invokeLater(() -> LOG_AREA.append(message + System.lineSeparator()));
        System.out.println(message);
    }

    private static ServerConfig loadConfig() {
        var properties = new Properties();
        var configFile = Path.of("server_config.properties");
        if (Files.isRegularFile(configFile)) {
            try (var input = Files.newInputStream(configFile)) {
                properties.load(input);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read " + configFile, exception);
            }
        }
        return new ServerConfig(
                Integer.parseInt(properties.getProperty("port", "5000")),
                Path.of(properties.getProperty("storage_dir", "server_storage")));
    }

    record ServerConfig(int port, Path storageDirectory) {
        ServerConfig {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
        }
    }
}

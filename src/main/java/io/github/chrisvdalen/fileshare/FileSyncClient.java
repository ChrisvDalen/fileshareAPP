package io.github.chrisvdalen.fileshare;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class FileSyncClient {
    private static final JTextArea LOG_AREA = new JTextArea();
    private static final JProgressBar PROGRESS_BAR = new JProgressBar(0, 100);
    private static final DefaultListModel<String> FILES = new DefaultListModel<>();
    private static ClientConfig config;

    private FileSyncClient() {
    }

    public static void main(String[] args) {
        config = loadConfig();
        SwingUtilities.invokeLater(FileSyncClient::setupGui);
    }

    private static void setupGui() {
        var frame = new JFrame("File Sync Client");
        frame.setSize(600, 420);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        var panel = new JPanel(new BorderLayout());
        LOG_AREA.setEditable(false);
        panel.add(new JScrollPane(LOG_AREA), BorderLayout.CENTER);
        PROGRESS_BAR.setStringPainted(true);
        panel.add(PROGRESS_BAR, BorderLayout.SOUTH);

        var uploadButton = new JButton("Select files to upload");
        uploadButton.addActionListener(event -> selectFiles(frame));
        panel.add(uploadButton, BorderLayout.NORTH);

        var fileList = new JList<>(FILES);
        fileList.setDropTarget(new DropTarget() {
            @Override
            public void drop(DropTargetDropEvent event) {
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    var files = (List<File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    files.forEach(FileSyncClient::sendInBackground);
                    event.dropComplete(true);
                } catch (Exception exception) {
                    event.dropComplete(false);
                    log("Drag and drop failed: " + exception.getMessage());
                }
            }
        });
        panel.add(new JScrollPane(fileList), BorderLayout.WEST);
        frame.add(panel);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static void selectFiles(JFrame parent) {
        var chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            for (var file : chooser.getSelectedFiles()) {
                sendInBackground(file);
            }
        }
    }

    private static void sendInBackground(File file) {
        Thread.ofVirtual().name("upload-" + file.getName()).start(() -> sendFile(file.toPath()));
    }

    private static void sendFile(Path file) {
        try (var socket = new Socket(config.serverAddress(), config.port());
             var output = new DataOutputStream(socket.getOutputStream());
             var input = new DataInputStream(socket.getInputStream());
             var fileInput = Files.newInputStream(file)) {
            var size = Files.size(file);
            var lastModified = Files.getLastModifiedTime(file).toMillis();
            output.writeUTF(file.getFileName().toString());
            output.writeLong(size);
            output.writeLong(lastModified);
            output.flush();

            if (!input.readBoolean()) {
                log("File already exists: " + file.getFileName());
                return;
            }

            var buffer = new byte[16 * 1024];
            long sent = 0;
            for (int read; (read = fileInput.read(buffer)) >= 0; ) {
                output.write(buffer, 0, read);
                sent += read;
                var percentage = size == 0 ? 100 : (int) Math.min(100, sent * 100 / size);
                SwingUtilities.invokeLater(() -> PROGRESS_BAR.setValue(percentage));
            }
            output.flush();
            log("File sent: " + file.getFileName());
            SwingUtilities.invokeLater(() -> FILES.addElement("Uploaded: " + file.getFileName()));
        } catch (IOException exception) {
            log("Upload failed: " + exception.getMessage());
        }
    }

    private static void log(String message) {
        SwingUtilities.invokeLater(() -> LOG_AREA.append(message + System.lineSeparator()));
        System.out.println(message);
    }

    private static ClientConfig loadConfig() {
        var properties = new Properties();
        var configFile = Path.of("client_config.properties");
        if (Files.isRegularFile(configFile)) {
            try (var input = Files.newInputStream(configFile)) {
                properties.load(input);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read " + configFile, exception);
            }
        }
        return new ClientConfig(
                properties.getProperty("server_address", "localhost"),
                Integer.parseInt(properties.getProperty("port", "5000")));
    }

    record ClientConfig(String serverAddress, int port) {
        ClientConfig {
            if (serverAddress == null || serverAddress.isBlank()) {
                throw new IllegalArgumentException("Server address is required");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
        }
    }
}

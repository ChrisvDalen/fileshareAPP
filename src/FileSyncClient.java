import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.List;

public class FileSyncClient {
    private static String SERVER_ADDRESS;
    private static int PORT;
    private static String CLIENT_FOLDER;
    private static JTextArea logArea;
    private static JProgressBar progressBar;
    private static DefaultListModel<String> fileListModel;

    public static void main(String[] args) {
        loadConfig();
        setupGUI();
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
                        sendFile(file);
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
                        sendFile(file);
                    }
                } catch (Exception ex) {
                    log("Drag & Drop failed: " + ex.getMessage());
                }
            }
        });

        frame.add(panel);
        frame.setVisible(true);
    }

    private static void sendFile(File file) {
        try (Socket socket = new Socket(SERVER_ADDRESS, PORT);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(file)) {

            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            dos.writeLong(file.lastModified());

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
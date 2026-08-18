package io.github.chrisvdalen.fileshare;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class FileReceiver {
    static final long MAX_FILE_SIZE = 1L << 30;

    private FileReceiver() {
    }

    static Path safeTarget(Path storageDirectory, String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IOException("Invalid file name");
        }

        var storage = storageDirectory.toAbsolutePath().normalize();
        var target = storage.resolve(fileName).normalize();
        if (!target.getParent().equals(storage)) {
            throw new IOException("File must remain inside the storage directory");
        }
        return target;
    }

    static void receive(DataInputStream input, Path target, long size, long lastModified) throws IOException {
        if (size < 0 || size > MAX_FILE_SIZE) {
            throw new IOException("Invalid file size: " + size);
        }

        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                copyExactly(input, output, size);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(lastModified));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long size) throws IOException {
        var buffer = new byte[16 * 1024];
        var remaining = size;
        while (remaining > 0) {
            var read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Upload ended before the declared file size");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }
}

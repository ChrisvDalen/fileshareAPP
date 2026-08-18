package io.github.chrisvdalen.fileshare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileReceiverTest {
    @TempDir
    Path storage;

    @Test
    void rejectsPathTraversal() {
        assertThrows(IOException.class, () -> FileReceiver.safeTarget(storage, "../secret.txt"));
        assertThrows(IOException.class, () -> FileReceiver.safeTarget(storage, "nested/file.txt"));
    }

    @Test
    void receivesExactlyTheDeclaredNumberOfBytes() throws IOException {
        var payload = "modern Java file transfer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var target = FileReceiver.safeTarget(storage, "example.txt");

        FileReceiver.receive(new DataInputStream(new ByteArrayInputStream(payload)), target, payload.length, 123_000L);

        assertArrayEquals(payload, Files.readAllBytes(target));
        assertEquals(123_000L, Files.getLastModifiedTime(target).toMillis());
    }

    @Test
    void removesPartialUploadWhenTheStreamEndsEarly() {
        var target = storage.resolve("partial.txt");
        var input = new DataInputStream(new ByteArrayInputStream(new byte[]{1, 2}));

        assertThrows(IOException.class, () -> FileReceiver.receive(input, target, 3, 0));
        assertEquals(0, storage.toFile().list().length);
    }
}

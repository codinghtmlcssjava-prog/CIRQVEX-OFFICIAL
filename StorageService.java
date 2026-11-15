
package com.redx;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.*;
import java.util.UUID;

@Service
public class StorageService {

    private final Path root;

    public StorageService(
        @Value("${app.storage-dir}") String directory
    ) throws Exception {
        root = Paths.get(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public String save(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File kosong");
        }

        String original = file.getOriginalFilename();
        String extension = "";

        if (original != null && original.contains(".")) {
            extension = original.substring(
                original.lastIndexOf(".")
            ).replaceAll("[^a-zA-Z0-9.]", "");
        }

        String filename = UUID.randomUUID() + extension;
        Path target = root.resolve(filename).normalize();

        if (!target.startsWith(root)) {
            throw new SecurityException("Lokasi file tidak valid");
        }

        Files.copy(
            file.getInputStream(),
            target,
            StandardCopyOption.REPLACE_EXISTING
        );

        return target.toString();
    }

    public Resource load(String storedPath) {
        Path path = Paths.get(storedPath).toAbsolutePath().normalize();

        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File tidak ditemukan");
        }

        return new FileSystemResource(path);
    }
}

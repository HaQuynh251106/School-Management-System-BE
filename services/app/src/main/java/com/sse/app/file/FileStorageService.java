package com.sse.app.file;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;

@Service
public class FileStorageService {
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp",
            "text/plain", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StoredFileRepository repository;
    private final Path root;

    public FileStorageService(StoredFileRepository repository,
                              @Value("${sse.storage.path}") String storagePath) throws IOException {
        this.repository = repository;
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public StoredFile store(MultipartFile upload, String userId) {
        if (upload.isEmpty()) throw ApiException.badRequest("Tệp tải lên trống");
        String type = upload.getContentType() == null ? "application/octet-stream" : upload.getContentType();
        if (!ALLOWED_TYPES.contains(type)) throw ApiException.badRequest("Định dạng tệp không được hỗ trợ");
        String original = Path.of(upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename())
                .getFileName().toString();
        String id = Ids.gen("file");
        String storageName = id + extension(original);
        Path target = root.resolve(storageName).normalize();
        if (!target.startsWith(root)) throw ApiException.badRequest("Tên tệp không hợp lệ");
        try (var input = upload.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw ApiException.serviceUnavailable("Không thể lưu tệp");
        }
        return repository.save(StoredFile.builder().id(id).originalName(original)
                .storageName(storageName).contentType(type).sizeBytes(upload.getSize())
                .uploadedBy(userId).createdAt(Instant.now()).build());
    }

    public StoredFile metadata(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Tệp"));
    }

    public StoredFile ownedMetadata(String id, String userId) {
        StoredFile file = metadata(id);
        if (!file.getUploadedBy().equals(userId)) {
            throw ApiException.forbidden("Không có quyền sử dụng tệp này");
        }
        return file;
    }

    public Resource content(StoredFile file) {
        try {
            Resource resource = new UrlResource(root.resolve(file.getStorageName()).toUri());
            if (!resource.exists() || !resource.isReadable()) throw ApiException.notFound("Tệp");
            return resource;
        } catch (IOException e) {
            throw ApiException.notFound("Tệp");
        }
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index).replaceAll("[^A-Za-z0-9.]", "").toLowerCase();
    }
}

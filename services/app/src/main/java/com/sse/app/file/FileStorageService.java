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
import java.util.Map;
import java.util.Set;

@Service
public class FileStorageService {
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/webp",
            "text/plain", "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            "application/pdf", Set.of(".pdf"), "image/jpeg", Set.of(".jpg", ".jpeg"),
            "image/png", Set.of(".png"), "image/webp", Set.of(".webp"), "text/plain", Set.of(".txt"),
            "application/msword", Set.of(".doc"), "application/vnd.ms-excel", Set.of(".xls"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of(".docx"),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of(".xlsx"));

    private final StoredFileRepository repository;
    private final Path root;
    private final long maxFileBytes;
    private final long userQuotaBytes;

    public FileStorageService(StoredFileRepository repository,
                              @Value("${sse.storage.path}") String storagePath,
                              @Value("${sse.storage.max-file-bytes:10485760}") long maxFileBytes,
                              @Value("${sse.storage.user-quota-bytes:104857600}") long userQuotaBytes) throws IOException {
        this.repository = repository;
        this.maxFileBytes = maxFileBytes;
        this.userQuotaBytes = userQuotaBytes;
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public StoredFile store(MultipartFile upload, String userId) {
        if (upload.isEmpty()) throw ApiException.badRequest("Tệp tải lên trống");
        if (upload.getSize() > maxFileBytes) throw ApiException.badRequest("Tệp vượt quá dung lượng cho phép 10 MB");
        if (repository.totalSizeByUploader(userId) + upload.getSize() > userQuotaBytes) {
            throw ApiException.badRequest("Tài khoản đã vượt hạn mức lưu trữ tệp");
        }
        String type = upload.getContentType() == null ? "application/octet-stream" : upload.getContentType();
        if (!ALLOWED_TYPES.contains(type)) throw ApiException.badRequest("Định dạng tệp không được hỗ trợ");
        String original = Path.of(upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename())
                .getFileName().toString();
        if (original.length() > 255) throw ApiException.badRequest("Tên tệp quá dài");
        String extension = extension(original);
        if (!EXTENSIONS.getOrDefault(type, Set.of()).contains(extension)) {
            throw ApiException.badRequest("Phần mở rộng tệp không khớp với định dạng khai báo");
        }
        validateSignature(upload, type);
        String id = Ids.gen("file");
        String storageName = id + extension;
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

    private void validateSignature(MultipartFile upload, String type) {
        byte[] prefix;
        try (var input = upload.getInputStream()) {
            prefix = input.readNBytes(16);
        } catch (IOException exception) {
            throw ApiException.badRequest("Không thể kiểm tra nội dung tệp");
        }
        boolean valid = switch (type) {
            case "application/pdf" -> startsWith(prefix, new byte[]{'%', 'P', 'D', 'F', '-'});
            case "image/jpeg" -> startsWith(prefix, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/png" -> startsWith(prefix, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/webp" -> prefix.length >= 12 && startsWith(prefix, new byte[]{'R', 'I', 'F', 'F'})
                    && prefix[8] == 'W' && prefix[9] == 'E' && prefix[10] == 'B' && prefix[11] == 'P';
            case "application/msword", "application/vnd.ms-excel" -> startsWith(prefix,
                    new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> startsWith(prefix, new byte[]{'P', 'K'});
            case "text/plain" -> java.util.stream.IntStream.range(0, prefix.length).noneMatch(index -> prefix[index] == 0);
            default -> false;
        };
        if (!valid) throw ApiException.badRequest("Nội dung thực tế của tệp không đúng định dạng");
    }

    private boolean startsWith(byte[] value, byte[] signature) {
        if (value.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) if (value[index] != signature[index]) return false;
        return true;
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

package com.sse.app.file;

import com.sse.app.common.ApiException;
import com.sse.app.common.Ids;
import com.sse.app.file.FileDtos.PresignDownloadResponse;
import com.sse.app.file.FileDtos.PresignUploadRequest;
import com.sse.app.file.FileDtos.PresignUploadResponse;
import com.sse.app.file.FileDtos.StoredFileResponse;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class FileStorageService {
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg"),
            "png", Set.of("image/png")
    );

    private final MinioClient minio;
    private final MinioStorageProperties properties;
    private final StoredFileRepository files;

    public FileStorageService(MinioClient minio, MinioStorageProperties properties, StoredFileRepository files) {
        this.minio = minio;
        this.properties = properties;
        this.files = files;
    }

    @Transactional
    public PresignUploadResponse createUploadUrl(PresignUploadRequest request, String uploaderId, String role) {
        String scope = validateScope(request.scope(), role);
        validateDeclaredFile(scope, request.fileName(), request.contentType(), request.sizeBytes());
        ensureBucket();

        String id = Ids.gen("file");
        String fileKey = keyFor(scope, request.fileName());
        Instant expiresAt = Instant.now().plusSeconds(properties.getPresignExpirySeconds());
        try {
            String uploadUrl = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.getBucket())
                    .object(fileKey)
                    .expiry(properties.getPresignExpirySeconds(), TimeUnit.SECONDS)
                    .build());
            files.save(StoredFile.builder()
                    .id(id)
                    .fileKey(fileKey)
                    .scope(scope)
                    .originalName(request.fileName().trim())
                    .contentType(normalizeContentType(request.contentType()))
                    .sizeBytes(request.sizeBytes())
                    .uploadedBy(uploaderId)
                    .status("PENDING_UPLOAD")
                    .createdAt(Instant.now())
                    .build());
            return new PresignUploadResponse(id, fileKey, uploadUrl, expiresAt, "PUT");
        } catch (Exception ex) {
            throw storageUnavailable(ex);
        }
    }

    @Transactional
    public StoredFileResponse completeUpload(String id, String actorId, boolean isAdmin) {
        StoredFile file = getForOwner(id, actorId, isAdmin);
        if ("READY".equals(file.getStatus())) return toResponse(file);

        try {
            StatObjectResponse object = minio.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(file.getFileKey())
                    .build());
            String actualContentType = normalizeContentType(object.contentType());
            if (object.size() != file.getSizeBytes()) {
                throw ApiException.badRequest("Dung lượng file tải lên không khớp với yêu cầu");
            }
            if (!file.getContentType().equals(actualContentType)) {
                throw ApiException.badRequest("Định dạng file tải lên không khớp với yêu cầu");
            }
            file.setStatus("READY");
            file.setCompletedAt(Instant.now());
            return toResponse(files.save(file));
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw storageUnavailable(ex);
        }
    }

    public PresignDownloadResponse createDownloadUrl(String id, String actorId, boolean isAdmin) {
        StoredFile file = getForOwner(id, actorId, isAdmin);
        if (!"READY".equals(file.getStatus())) {
            throw ApiException.badRequest("File chưa hoàn tất tải lên");
        }
        ensureBucket();
        Instant expiresAt = Instant.now().plusSeconds(properties.getPresignExpirySeconds());
        try {
            String downloadUrl = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(file.getFileKey())
                    .expiry(properties.getPresignExpirySeconds(), TimeUnit.SECONDS)
                    .build());
            return new PresignDownloadResponse(downloadUrl, expiresAt);
        } catch (Exception ex) {
            throw storageUnavailable(ex);
        }
    }

    /**
     * Generates a temporary download link after a domain module has verified
     * the caller's access to the file.
     */
    public PresignDownloadResponse createDownloadUrlForAuthorizedAccess(String id) {
        StoredFile file = files.findById(id).orElseThrow(() -> ApiException.notFound("File"));
        if (!"READY".equals(file.getStatus())) {
            throw ApiException.badRequest("File is not ready for download");
        }
        ensureBucket();
        Instant expiresAt = Instant.now().plusSeconds(properties.getPresignExpirySeconds());
        try {
            String downloadUrl = minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(file.getFileKey())
                    .expiry(properties.getPresignExpirySeconds(), TimeUnit.SECONDS)
                    .build());
            return new PresignDownloadResponse(downloadUrl, expiresAt);
        } catch (Exception ex) {
            throw storageUnavailable(ex);
        }
    }

    /** Stores a server-generated receipt under a deterministic key for safe retries. */
    public StoredFile storeGeneratedReceipt(String fileName, byte[] content, String generatedBy) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("Nội dung biên nhận trống");
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw ApiException.badRequest("Biên nhận vượt quá 5 MB");
        }
        String originalLeaf = Path.of(fileName.trim()).getFileName().toString();
        String safeName = originalLeaf.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank()) safeName = "receipt.pdf";
        if (!safeName.toLowerCase(Locale.ROOT).endsWith(".pdf")) safeName += ".pdf";
        String fileKey = "receipts/" + safeName;

        ensureBucket();
        try {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(fileKey)
                    .contentType("application/pdf")
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .build());
            Instant now = Instant.now();
            StoredFile file = files.findByFileKey(fileKey).orElseGet(() -> StoredFile.builder()
                    .id(Ids.gen("file"))
                    .fileKey(fileKey)
                    .createdAt(now)
                    .build());
            file.setScope("PAYMENT_RECEIPT");
            file.setOriginalName(safeName);
            file.setContentType("application/pdf");
            file.setSizeBytes(content.length);
            file.setUploadedBy(generatedBy == null || generatedBy.isBlank() ? "SYSTEM" : generatedBy);
            file.setStatus("READY");
            file.setCompletedAt(now);
            return files.save(file);
        } catch (Exception ex) {
            throw storageUnavailable(ex);
        }
    }

    /**
     * Verifies that a completed file belongs to the caller before another
     * domain module links it to its aggregate.
     */
    public StoredFile requireReadyOwnedFile(String id, String expectedScope, String ownerId) {
        StoredFile file = files.findById(id).orElseThrow(() -> ApiException.notFound("File"));
        if (!expectedScope.equals(file.getScope())) {
            throw ApiException.badRequest("File không đúng loại sử dụng");
        }
        if (!ownerId.equals(file.getUploadedBy())) {
            throw ApiException.forbidden("Bạn không có quyền sử dụng file này");
        }
        if (!"READY".equals(file.getStatus())) {
            throw ApiException.badRequest("File chưa hoàn tất tải lên");
        }
        return file;
    }

    private StoredFile getForOwner(String id, String actorId, boolean isAdmin) {
        StoredFile file = files.findById(id).orElseThrow(() -> ApiException.notFound("File"));
        if (!isAdmin && !file.getUploadedBy().equals(actorId)) {
            throw ApiException.forbidden("Bạn không có quyền truy cập file này");
        }
        return file;
    }

    private void ensureBucket() {
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception ex) {
            throw storageUnavailable(ex);
        }
    }

    private String validateScope(String rawScope, String role) {
        String scope = rawScope.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ASSIGNMENT", "SUBMISSION", "PAYMENT_PROOF").contains(scope)) {
            throw ApiException.badRequest("scope chỉ nhận ASSIGNMENT, SUBMISSION hoặc PAYMENT_PROOF");
        }
        if ("PAYMENT_PROOF".equals(scope) && !"PARENT".equals(role)) {
            throw ApiException.forbidden("Chỉ phụ huynh mới được tải biên lai thanh toán");
        }
        if ("ADMIN".equals(role)) return scope;
        if ("ASSIGNMENT".equals(scope) && !("TEACHER".equals(role) || "ADMIN".equals(role))) {
            throw ApiException.forbidden("Chỉ giáo viên mới được tải file bài tập");
        }
        if ("SUBMISSION".equals(scope) && !"STUDENT".equals(role)) {
            throw ApiException.forbidden("Chỉ học sinh mới được tải file bài nộp");
        }
        return scope;
    }

    private void validateDeclaredFile(String scope, String fileName, String contentType, long sizeBytes) {
        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            throw ApiException.badRequest("Mỗi file tối đa 5 MB");
        }
        String extension = extensionOf(fileName);
        String normalizedType = normalizeContentType(contentType);
        if (!ALLOWED_TYPES.containsKey(extension) || !ALLOWED_TYPES.get(extension).contains(normalizedType)) {
            throw ApiException.badRequest("Chỉ nhận PDF, DOCX, JPG hoặc PNG với đúng định dạng file");
        }
        if ("PAYMENT_PROOF".equals(scope) && !Set.of("jpg", "jpeg", "png").contains(extension)) {
            throw ApiException.badRequest("Biên lai chỉ nhận ảnh JPG hoặc PNG");
        }
    }

    private String keyFor(String scope, String fileName) {
        String originalLeaf = Path.of(fileName.trim()).getFileName().toString();
        String safeName = originalLeaf.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeName.isBlank()) safeName = "upload";
        String folder = switch (scope) {
            case "ASSIGNMENT" -> "assignments";
            case "PAYMENT_PROOF" -> "payment-proofs";
            default -> "submissions";
        };
        return folder + "/" + UUID.randomUUID() + "-" + safeName;
    }

    private String extensionOf(String fileName) {
        String leaf = Path.of(fileName.trim()).getFileName().toString();
        int dot = leaf.lastIndexOf('.');
        if (dot < 1 || dot == leaf.length() - 1) return "";
        return leaf.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private ApiException storageUnavailable(Exception ex) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MinIO chưa sẵn sàng: " + ex.getMessage());
    }

    private StoredFileResponse toResponse(StoredFile file) {
        return new StoredFileResponse(file.getId(), file.getFileKey(), file.getScope(), file.getOriginalName(),
                file.getContentType(), file.getSizeBytes(), file.getStatus(), file.getCreatedAt(), file.getCompletedAt());
    }
}

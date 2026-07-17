package com.sse.app.file;

import com.sse.app.file.FileDtos.PresignDownloadResponse;
import com.sse.app.file.FileDtos.PresignUploadRequest;
import com.sse.app.file.FileDtos.PresignUploadResponse;
import com.sse.app.file.FileDtos.StoredFileResponse;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileStorageController {
    private final FileStorageService storage;

    public FileStorageController(FileStorageService storage) {
        this.storage = storage;
    }

    @PostMapping("/files/presigned-upload")
    public PresignUploadResponse presignUpload(@Valid @RequestBody PresignUploadRequest request) {
        CurrentUser me = CurrentUserHolder.require();
        return storage.createUploadUrl(request, me.id(), me.role());
    }

    @PostMapping("/files/{id}/complete")
    public StoredFileResponse complete(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        return storage.completeUpload(id, me.id(), me.isAdmin());
    }

    @PostMapping("/files/{id}/presigned-download")
    public PresignDownloadResponse presignDownload(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        return storage.createDownloadUrl(id, me.id(), me.isAdmin());
    }
}

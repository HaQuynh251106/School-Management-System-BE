package com.sse.app.file;

import com.sse.app.academic.assignment.AssignmentService;
import com.sse.app.chat.ChatService;
import com.sse.app.file.FileDtos.FileMetadata;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/files")
public class FileController {
    private final FileStorageService storage;
    private final AssignmentService assignments;
    private final ChatService chat;

    public FileController(FileStorageService storage, AssignmentService assignments, ChatService chat) {
        this.storage = storage;
        this.assignments = assignments;
        this.chat = chat;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileMetadata upload(@RequestPart("file") MultipartFile file) {
        return FileMetadata.from(storage.store(file, CurrentUserHolder.require().id()));
    }

    @GetMapping("/{id}")
    public FileMetadata metadata(@PathVariable String id) {
        StoredFile file = storage.metadata(id);
        if (!chat.canAccessFile(id, CurrentUserHolder.require())) assignments.assertCanAccessFile(file, CurrentUserHolder.require());
        return FileMetadata.from(file);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable String id) {
        StoredFile file = storage.metadata(id);
        if (!chat.canAccessFile(id, CurrentUserHolder.require())) assignments.assertCanAccessFile(file, CurrentUserHolder.require());
        MediaType type;
        try { type = MediaType.parseMediaType(file.getContentType()); }
        catch (Exception ignored) { type = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .body(storage.content(file));
    }
}

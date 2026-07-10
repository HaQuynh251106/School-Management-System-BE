package com.sse.app.identity;

import com.sse.app.identity.StudentImportService.StudentImportResult;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/users")
public class UserImportController {

    private final StudentImportService imports;

    public UserImportController(StudentImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StudentImportResult importStudents(@RequestParam("file") MultipartFile file) {
        CurrentUserHolder.requireRole("ADMIN");
        return imports.importStudents(file);
    }
}

package com.sse.identity.controller;

import com.sse.identity.dto.request.CreateRoleRequest;
import com.sse.identity.dto.response.RoleResponse;
import com.sse.identity.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller — nhận HTTP request, gọi Service, trả response.
 *
 * Quy ước:
 *  - @RestController = @Controller + @ResponseBody (auto serialize JSON).
 *  - @RequestMapping("/roles") = mọi endpoint trong class này bắt đầu bằng /roles.
 *  - Không chứa logic nghiệp vụ — chỉ là "router" mỏng.
 *  - @Valid bật validation cho @NotBlank/@Size trong DTO.
 */
@RestController
@RequestMapping("/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** GET /roles — trả về tất cả role. */
    @GetMapping
    public List<RoleResponse> getAll() {
        return roleService.getAll();
    }

    /** POST /roles — tạo role mới. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse create(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.create(request);
    }
}

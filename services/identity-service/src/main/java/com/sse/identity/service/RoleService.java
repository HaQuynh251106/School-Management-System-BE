package com.sse.identity.service;

import com.sse.identity.dto.request.CreateRoleRequest;
import com.sse.identity.dto.response.RoleResponse;
import com.sse.identity.entity.Role;
import com.sse.identity.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service — chứa logic nghiệp vụ.
 *
 * Quy ước:
 *  - Controller KHÔNG gọi thẳng Repository, phải đi qua Service.
 *  - Mọi method ghi DB phải @Transactional.
 *  - Convert entity ↔ DTO ngay tại đây (hoặc dùng MapStruct nếu nhiều).
 *
 * Lombok:
 *  - @RequiredArgsConstructor tự sinh constructor cho mọi field `final`,
 *    để Spring inject dependency qua constructor (best practice).
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;

    @Transactional(readOnly = true)
    public List<RoleResponse> getAll() {
        return roleRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public RoleResponse create(CreateRoleRequest request) {
        if (roleRepository.existsByCode(request.getCode())) {
            throw new IllegalArgumentException("Role code đã tồn tại: " + request.getCode());
        }

        Role role = new Role();
        role.setCode(request.getCode());
        role.setName(request.getName());
        role.setDescription(request.getDescription());

        Role saved = roleRepository.save(role);
        return toResponse(saved);
    }

    /** Convert entity → DTO. Khi nhiều entity, hãy tách ra class Mapper riêng. */
    private RoleResponse toResponse(Role role) {
        RoleResponse r = new RoleResponse();
        r.setId(role.getId());
        r.setCode(role.getCode());
        r.setName(role.getName());
        r.setDescription(role.getDescription());
        r.setCreatedAt(role.getCreatedAt());
        return r;
    }
}

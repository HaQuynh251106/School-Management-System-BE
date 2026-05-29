package com.sse.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * DTO nhận body từ client khi tạo Role mới.
 *
 * Vì sao không dùng thẳng Entity Role làm input?
 *  - Tách lớp: client KHÔNG được set id / created_at.
 *  - Validate ở đây chỉ áp cho INPUT, không ảnh hưởng entity.
 *
 * @Data của Lombok = @Getter + @Setter + @ToString + @EqualsAndHashCode.
 */
@Data
public class CreateRoleRequest {

    @NotBlank(message = "code không được rỗng")
    @Size(max = 50, message = "code tối đa 50 ký tự")
    private String code;

    @NotBlank(message = "name không được rỗng")
    @Size(max = 100)
    private String name;

    private String description;
}

package com.sse.app.identity;

import com.sse.app.common.ApiException;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Hồ sơ của chính người đang đăng nhập + danh sách con (D1). */
@RestController
public class MeController {

    private final UserService users;

    public MeController(UserService users) {
        this.users = users;
    }

    @GetMapping("/me")
    public UserDto me() {
        return users.dtoById(CurrentUserHolder.require().id());
    }

    @GetMapping("/me/children")
    public List<UserDto> children() {
        CurrentUser me = CurrentUserHolder.require();
        if (!me.isParent()) throw ApiException.forbidden("Chỉ phụ huynh");
        return users.childrenOf(me.id());
    }
}

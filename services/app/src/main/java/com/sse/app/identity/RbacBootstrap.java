package com.sse.app.identity;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class RbacBootstrap {
    @Bean
    @Order(30)
    ApplicationRunner rbacUserRoleBackfill(
            UserRepository users, UserRoleRepository userRoles, RbacService rbac) {
        return args -> {
            for (User user : users.findAll()) {
                if (userRoles.findByUserId(user.getId()).isEmpty()) {
                    rbac.assignPrimaryRole(user.getId(), user.getRole(), null);
                }
            }
        };
    }
}

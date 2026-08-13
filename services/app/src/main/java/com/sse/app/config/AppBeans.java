package com.sse.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

@Configuration
public class AppBeans {

    /** Chỉ dùng BCrypt từ spring-security-crypto, không bật full Spring Security. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public Clock systemClock(Environment environment) {
        String fixedInstant = environment.getProperty("sse.demo.fixed-clock");
        if (environment.acceptsProfiles(Profiles.of("demo"))
                && fixedInstant != null && !fixedInstant.isBlank()) {
            return Clock.fixed(Instant.parse(fixedInstant.trim()), ZoneOffset.UTC);
        }
        return Clock.systemUTC();
    }

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notification-");
        executor.initialize();
        return executor;
    }
}

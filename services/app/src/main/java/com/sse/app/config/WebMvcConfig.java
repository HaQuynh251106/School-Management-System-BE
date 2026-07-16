package com.sse.app.config;

import com.sse.app.audit.AuditMutationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuditMutationInterceptor audit;

    public WebMvcConfig(AuditMutationInterceptor audit) {
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(audit).addPathPatterns("/**");
    }
}

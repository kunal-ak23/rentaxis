package com.datagami.rentaxis.config;

import com.datagami.rentaxis.core.tenant.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public WebMvcConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Apply tenant interceptor to standard API routes, excluding public auth or
        // global admin routes
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/v1/**");
    }
}

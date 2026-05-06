package com.datagami.rentaxis.core.email.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

@Configuration
public class EmailMessageSourceConfig {

    @Bean(name = "emailMessageSource")
    public MessageSource emailMessageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages/email");
        ms.setDefaultEncoding("UTF-8");
        ms.setUseCodeAsDefaultMessage(false);
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}

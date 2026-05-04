package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureOpenAIClientConfig {

    @Bean
    @ConditionalOnProperty(prefix = "azure.openai", name = "endpoint")
    public OpenAIClient openAIClient(AzureOpenAIConfig cfg) {
        return new OpenAIClientBuilder()
                .endpoint(cfg.getEndpoint())
                .credential(new AzureKeyCredential(cfg.getApiKey()))
                .buildClient();
    }
}

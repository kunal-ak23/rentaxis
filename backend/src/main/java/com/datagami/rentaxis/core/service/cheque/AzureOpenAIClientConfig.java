package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureOpenAIClientConfig {

    @Bean
    @ConditionalOnExpression(
            "T(org.springframework.util.StringUtils).hasText('${azure.openai.endpoint:}') and " +
            "T(org.springframework.util.StringUtils).hasText('${azure.openai.api-key:}')"
    )
    public OpenAIClient openAIClient(AzureOpenAIConfig cfg) {
        return new OpenAIClientBuilder()
                .endpoint(cfg.getEndpoint())
                .credential(new AzureKeyCredential(cfg.getApiKey()))
                .buildClient();
    }
}

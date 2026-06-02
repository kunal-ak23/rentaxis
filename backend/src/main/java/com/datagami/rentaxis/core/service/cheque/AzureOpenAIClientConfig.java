package com.datagami.rentaxis.core.service.cheque;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.OpenAIServiceVersion;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.http.okhttp.OkHttpAsyncHttpClientBuilder;
import com.datagami.rentaxis.core.config.AzureOpenAIConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
                .serviceVersion(parseVersion(cfg.getApiVersion()))
                // Use OkHttp instead of the default Netty client: the Netty sync
                // path throws "channel not registered to an event loop" on
                // connection cleanup under repeated/concurrent calls, which broke
                // bulk cheque OCR. OkHttp is thread-safe for sync use.
                .httpClient(new OkHttpAsyncHttpClientBuilder().build())
                .buildClient();
    }

    @Bean
    @ConditionalOnBean(OpenAIClient.class)
    public ChequeExtractor azureChequeExtractor(OpenAIClient client, AzureOpenAIConfig cfg) {
        return new AzureOpenAIChequeExtractor(client, cfg);
    }

    @Bean
    @ConditionalOnMissingBean(ChequeExtractor.class)
    public ChequeExtractor unavailableChequeExtractor() {
        return new UnavailableChequeExtractor();
    }

    private static OpenAIServiceVersion parseVersion(String v) {
        if (v == null || v.isBlank()) {
            return OpenAIServiceVersion.getLatest();
        }
        for (OpenAIServiceVersion sv : OpenAIServiceVersion.values()) {
            if (sv.getVersion().equals(v)) {
                return sv;
            }
        }
        return OpenAIServiceVersion.getLatest();
    }
}

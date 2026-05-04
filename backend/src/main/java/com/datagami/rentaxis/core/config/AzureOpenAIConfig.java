package com.datagami.rentaxis.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "azure.openai")
public class AzureOpenAIConfig {
    private String endpoint = "";
    private String apiKey = "";
    private String deployment = "gpt-4o";
    private String apiVersion = "2024-10-21";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDeployment() {
        return deployment;
    }

    public void setDeployment(String deployment) {
        this.deployment = deployment;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }
}

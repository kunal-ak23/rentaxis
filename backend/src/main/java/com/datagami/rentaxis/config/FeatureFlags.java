package com.datagami.rentaxis.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rentaxis.features")
public class FeatureFlags {

    private boolean listingsEnabled;
}

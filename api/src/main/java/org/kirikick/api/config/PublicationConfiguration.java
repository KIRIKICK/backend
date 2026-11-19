package org.kirikick.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

// Import explicitly for publication verification; don't change existing application startup.
@EnableConfigurationProperties(PublicationProperties.class)
public class PublicationConfiguration {}

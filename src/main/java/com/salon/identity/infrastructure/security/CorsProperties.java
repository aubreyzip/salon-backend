package com.salon.identity.infrastructure.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Browser origins allowed to call the API (web clients). Mobile apps do not need CORS.
 */
@ConfigurationProperties("salon.security.cors")
record CorsProperties(@DefaultValue List<String> allowedOrigins) {
}

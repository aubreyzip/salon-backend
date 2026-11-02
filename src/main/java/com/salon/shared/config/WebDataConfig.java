package com.salon.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

/**
 * Serializes {@code Page} results as a stable {@code PagedModel} DTO
 * ({@code {"content": [...], "page": {...}}}) instead of the internal {@code PageImpl} structure.
 */
@Configuration(proxyBeanMethods = false)
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
class WebDataConfig {

}

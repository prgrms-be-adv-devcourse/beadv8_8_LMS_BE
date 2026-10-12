package com.hapbang.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.SpringDataWebSettings;

/**
 * 컨트롤러가 Page를 반환하면 PagedModel 형태({content, page: {size, number, totalElements, totalPages}})로 응답한다.
 * 페이지 요청은 Pageable(page, size, sort)로 받는다 (2026-10-12 회의: Pageable 표준).
 */
// yaml 속성(spring.data.web.pageable.serialization-mode)은 apps에만 있어 모듈 테스트에 적용되지 않으므로 빈으로 둔다.
@Configuration(proxyBeanMethods = false)
public class PageConfig {

    @Bean
    SpringDataWebSettings springDataWebSettings() {
        return new SpringDataWebSettings(PageSerializationMode.VIA_DTO);
    }
}

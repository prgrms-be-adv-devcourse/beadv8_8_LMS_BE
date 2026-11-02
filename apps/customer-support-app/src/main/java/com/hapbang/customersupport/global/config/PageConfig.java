package com.hapbang.customersupport.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.data.web.config.SpringDataWebSettings;

/**
 * 컨트롤러가 Page를 반환하면 PagedModel 형태({content, page: {size, number, totalElements, totalPages}})로 응답한다.
 * 페이지 요청은 Pageable(page, size, sort)로 받는다 (2026-10-12 회의: Pageable 표준).
 */
// yaml 속성(spring.data.web.pageable.*)은 apps에만 있어 모듈 테스트에 적용되지 않으므로 빈으로 둔다.
// 두 빈 모두 Boot의 같은 타입 빈을 대체하므로, spring.data.web.pageable.* 속성을 쓰려면 이 클래스에서 설정한다.
@Configuration(proxyBeanMethods = false)
public class PageConfig {

    /** 한 페이지 최대 크기. 넘게 요청하면 이 크기로 줄인다. 기본 크기는 Spring Data 기본값 20 */
    static final int MAX_PAGE_SIZE = 100;

    @Bean
    SpringDataWebSettings springDataWebSettings() {
        return new SpringDataWebSettings(PageSerializationMode.VIA_DTO);
    }

    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> resolver.setMaxPageSize(MAX_PAGE_SIZE);
    }
}

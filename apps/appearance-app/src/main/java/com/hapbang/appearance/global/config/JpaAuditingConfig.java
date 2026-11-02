package com.hapbang.appearance.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** JPA Auditing 활성화 (BaseEntity 생성일·수정일) */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}

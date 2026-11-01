package com.hapbang.billing.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hapbang.testsupport.IntegrationTest;

// 지갑은 Spring Event를 발행하지도 받지도 않는다(회원 이벤트는 Kafka, #430). Modulith 이벤트 기록 자동 설정을 빼 두었는지 확인한다 (#471).
// 켜져 있으면 Flyway가 지운 event_publication 테이블을 종료 때 레지스트리가 조회하다 실패한다.
// Modulith 이벤트 core 타입은 테스트 컴파일 경로에 없어 빈 이름으로 확인한다.
@IntegrationTest
class ModulithEventsDisabledTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Modulith 이벤트 기록 빈(레지스트리·저장소)이 없고 billing 스키마에 event_publication 테이블이 없다")
    void eventPublicationDisabled() {
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> name.toLowerCase().contains("eventpublication")))
                .isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'billing' AND table_name IN ('event_publication', 'event_publication_archive')""",
                Integer.class)).isZero();
    }
}

package com.hapbang.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @Bean
    DynamicPropertyRegistrar postgresProperties() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            // 운영과 같이 스키마는 Flyway 마이그레이션으로 만들고, Hibernate는 엔티티와 테이블이 맞는지만 검사한다.
            // 모듈 테스트에서는 클래스패스에 있는 그 모듈의 마이그레이션만 실행된다.
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.flyway.baseline-on-migrate", () -> "true");
            registry.add("spring.flyway.baseline-version", () -> "0");
            registry.add("spring.flyway.out-of-order", () -> "true");
            // 테스트 DB 정리에서 테이블 없는 기본 네임스페이스를 건너뛴다 (빈 "truncate table " 경고 제거). 비우기 필터만 바뀐다
            registry.add("spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider",
                    TruncateSchemaFilterProvider.class::getName);
        };
    }
}

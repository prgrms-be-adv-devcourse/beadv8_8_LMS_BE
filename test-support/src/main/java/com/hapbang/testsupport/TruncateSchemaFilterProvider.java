package com.hapbang.testsupport;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.internal.DefaultSchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * 테스트 DB 정리({@link DatabaseCleanUp}, {@code SchemaManager.truncate()})에서 테이블이 없는 네임스페이스를 건너뛴다.
 * <p>
 * 우리 엔티티는 모두 스키마를 명시해서(@Table(schema = ...)) Hibernate가 항상 등록하는 기본 네임스페이스(스키마 없음)에는 테이블이
 * 0개다. Hibernate 7.4의 SchemaTruncatorImpl은 네임스페이스마다 빈 목록 검사 없이 {@code truncate table} 문을 만들어,
 * PostgreSQL이 빈 {@code "truncate table "}을 거절하고 테스트마다 경고(Error executing DDL)가 남는다.
 * 테이블이 하나라도 있는 네임스페이스는 그대로 비우므로, 나중에 스키마 없는 엔티티가 생겨도 정리 대상에서 빠지지 않는다.
 * <p>
 * 비우기(truncate) 필터만 바꾸고 검증(ddl-auto=validate)·생성·삭제·마이그레이션 필터는 기본(전부 포함)을 그대로 쓴다.
 * {@link PostgresTestcontainersConfig}가 {@code hibernate.hbm2ddl.schema_filter_provider}로 등록한다.
 */
public class TruncateSchemaFilterProvider implements SchemaFilterProvider {

    private static final SchemaFilter SKIP_EMPTY_NAMESPACE = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return namespace.getTables().stream().anyMatch(Table::isPhysicalTable);
        }

        @Override
        public boolean includeTable(Table table) {
            return true;
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    @Override
    public SchemaFilter getCreateFilter() {
        return DefaultSchemaFilter.INSTANCE;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return DefaultSchemaFilter.INSTANCE;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return SKIP_EMPTY_NAMESPACE;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return DefaultSchemaFilter.INSTANCE;
    }

    @Override
    public SchemaFilter getValidateFilter() {
        return DefaultSchemaFilter.INSTANCE;
    }
}

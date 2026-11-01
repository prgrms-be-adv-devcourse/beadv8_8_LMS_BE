package com.hapbang.chat.out;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.TransactionSettings;
import org.springframework.core.env.Environment;

import com.hapbang.chat.fixture.ChatNativeSql;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;

/**
 * 마이그레이션 시험용 빈 데이터베이스. 테스트 공용 DB와 같은 PostgreSQL 컨테이너에 따로 만들고, 시험이 끝나면 지운다.
 * 테스트 공용 DB는 건드리지 않는다.
 * <ul>
 *     <li>SQL은 앱 엔티티를 매핑하지 않은 별도 JPA {@link EntityManagerFactory}의 native 쿼리로 실행한다({@link #sql}).</li>
 *     <li>{@code CREATE DATABASE}·{@code DROP DATABASE}는 트랜잭션 안에서 실행할 수 없어, 공용 DB 연결은 autocommit으로
 *     트랜잭션 없이 실행한다.</li>
 * </ul>
 */
final class ChatMigrationDatabase implements AutoCloseable {

    private final EntityManagerFactory admin;
    private final String name;
    private final String url;
    private final String user;
    private final String password;
    private final EntityManagerFactory database;

    /** 이 데이터베이스에 실행하는 native SQL */
    final ChatNativeSql sql;

    private ChatMigrationDatabase(Environment environment) {
        String adminUrl = environment.getRequiredProperty("spring.datasource.url");
        user = environment.getRequiredProperty("spring.datasource.username");
        password = environment.getRequiredProperty("spring.datasource.password");
        name = "chat_migration_" + UUID.randomUUID().toString().replace("-", "");
        admin = connect(adminUrl, user, password);
        runOutsideTransaction("create database " + name);
        url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        database = connect(url, user, password);
        sql = ChatNativeSql.of(database);
    }

    static ChatMigrationDatabase create(Environment environment) {
        return new ChatMigrationDatabase(environment);
    }

    /**
     * 마이그레이션을 적용한다.
     *
     * @param target 이 버전까지만 적용한다. null이면 끝까지
     */
    void migrate(String target) {
        var configuration = flywayConfiguration()
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(true);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    /** 이 데이터베이스에 연결한 Flyway 설정. 위치·스키마 등은 시험이 정한다 */
    FluentConfiguration flywayConfiguration() {
        return Flyway.configure().dataSource(url, user, password);
    }

    @Override
    public void close() {
        database.close();
        try {
            runOutsideTransaction("drop database if exists " + name + " with (force)");
        } finally {
            admin.close();
        }
    }

    private void runOutsideTransaction(String statement) {
        try (EntityManager entityManager = admin.createEntityManager()) {
            entityManager.createNativeQuery(statement).executeUpdate();
        }
    }

    private static EntityManagerFactory connect(String url, String user, String password) {
        return new PersistenceConfiguration("chat-migration-test")
                .property(PersistenceConfiguration.JDBC_URL, url)
                .property(PersistenceConfiguration.JDBC_USER, user)
                .property(PersistenceConfiguration.JDBC_PASSWORD, password)
                .properties(Map.of(
                        JdbcSettings.POOL_SIZE, 1,
                        JdbcSettings.AUTOCOMMIT, true,
                        TransactionSettings.ALLOW_UPDATE_OUTSIDE_TRANSACTION, true))
                .createEntityManagerFactory();
    }
}

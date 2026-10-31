package com.hapbang.chat.fixture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;

/**
 * 테스트가 엔티티로 만들 수 없는 상태(지난 시각, 옛 데이터, 제약을 끈 삭제)를 만들거나 DB 값을 그대로 확인할 때 쓰는
 * native SQL 실행기. JPA {@link EntityManager}의 native 쿼리로 실행한다.
 * <ul>
 *     <li>문장마다 트랜잭션(REQUIRED)으로 실행한다. 호출한 쪽 트랜잭션이 있으면 참여하고, 없으면 바로 커밋한다.</li>
 *     <li>앱 컨텍스트에서 실행한 문장의 오류는 repository처럼 Spring {@code DataAccessException}으로 바꿔 던진다.</li>
 *     <li>행 조회 결과는 컬럼 이름 → 값 Map이다.</li>
 * </ul>
 */
public final class ChatNativeSql {

    private static final PersistenceExceptionTranslator EXCEPTION_TRANSLATOR = new HibernateJpaDialect();

    private final Transactions transactions;

    private ChatNativeSql(Transactions transactions) {
        this.transactions = transactions;
    }

    /** 앱 컨텍스트의 공유 EntityManager와 트랜잭션 관리자로 실행한다 */
    public static ChatNativeSql of(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        return new ChatNativeSql(new Transactions() {
            @Override
            public <T> T call(Function<EntityManager, T> work) {
                try {
                    return transactionTemplate.execute(status -> work.apply(entityManager));
                } catch (RuntimeException e) {
                    throw DataAccessUtils.translateIfNecessary(e, EXCEPTION_TRANSLATOR);
                }
            }
        });
    }

    /** 앱 컨텍스트 밖의 EntityManagerFactory(마이그레이션 시험용 DB)로 실행한다 */
    public static ChatNativeSql of(EntityManagerFactory entityManagerFactory) {
        return new ChatNativeSql(new Transactions() {
            @Override
            public <T> T call(Function<EntityManager, T> work) {
                return entityManagerFactory.callInTransaction(work);
            }
        });
    }

    /** @return 바뀐 행 수 */
    public int update(String sql) {
        return update(sql, Map.of());
    }

    /** @return 바뀐 행 수 */
    public int update(String sql, Map<String, ?> params) {
        return transactions.call(entityManager -> bind(entityManager.createNativeQuery(sql), params).executeUpdate());
    }

    public <T> T single(String sql, Class<T> type) {
        return single(sql, type, Map.of());
    }

    @SuppressWarnings("unchecked")
    public <T> T single(String sql, Class<T> type, Map<String, ?> params) {
        return transactions.call(entityManager -> (T) bind(entityManager.createNativeQuery(sql, type), params)
                .getSingleResult());
    }

    public <T> List<T> list(String sql, Class<T> type) {
        return list(sql, type, Map.of());
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> list(String sql, Class<T> type, Map<String, ?> params) {
        return transactions.call(entityManager -> (List<T>) bind(entityManager.createNativeQuery(sql, type), params)
                .getResultList());
    }

    public Map<String, Object> row(String sql, Map<String, ?> params) {
        return toMap(single(sql, Tuple.class, params));
    }

    public List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        return list(sql, Tuple.class, params).stream()
                .map(ChatNativeSql::toMap)
                .toList();
    }

    private static Query bind(Query query, Map<String, ?> params) {
        params.forEach(query::setParameter);
        return query;
    }

    private static Map<String, Object> toMap(Tuple tuple) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (TupleElement<?> element : tuple.getElements()) {
            row.put(element.getAlias(), tuple.get(element));
        }
        return row;
    }

    /** 실행 단위 트랜잭션. 앱 컨텍스트는 Spring 트랜잭션, 컨텍스트 밖 DB는 JPA 트랜잭션을 쓴다 */
    private interface Transactions {

        <T> T call(Function<EntityManager, T> work);
    }
}

package com.hapbang.testsupport;

import jakarta.persistence.EntityManagerFactory;

/**
 * 테스트 데이터를 지운다. SQL을 직접 쓰지 않고 JPA 3.2 {@code SchemaManager.truncate()}를 사용한다.
 * <ul>
 *     <li>JPA로 매핑된 테이블만 비운다. 모듈 테스트에는 자기 모듈의 엔티티만 매핑되므로 그 모듈의 테이블만 비워진다.</li>
 *     <li>IDENTITY 컬럼의 ID는 초기화되지 않는다. 테스트는 특정 ID 값(1, 2...)에 의존하지 않고 저장 결과의 ID를 사용한다.</li>
 *     <li>Modulith 이벤트 기록은 {@link IntegrationTest}의 completion-mode=delete 설정으로 처리가 끝나면 바로 삭제된다.
 *     리스너가 실패한 이벤트만 남는다.</li>
 * </ul>
 * 테스트 코드에서 직접 호출할 일은 거의 없고, {@link DatabaseCleanUpListener}가 테스트마다 호출한다.
 */
public class DatabaseCleanUp {

    private final EntityManagerFactory entityManagerFactory;

    public DatabaseCleanUp(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    public void execute() {
        entityManagerFactory.getSchemaManager().truncate();
    }
}

package com.hapbang.chat.app;

import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatRecipientSet;

/**
 * 불변 수신자 집합 헤더 저장소. JPQL 조회는 {@link ChatRecipientSetRepositoryCustom}(QueryDSL)에 있다.
 */
public interface ChatRecipientSetRepository
        extends Repository<ChatRecipientSet, Long>, ChatRecipientSetRepositoryCustom {

    // 기본 메서드: 이름과 시그니처가 같아 Spring Data 기본 구현(SimpleJpaRepository)이 처리한다
    <S extends ChatRecipientSet> S save(S entity);
    Optional<ChatRecipientSet> findById(Long id);

    // 테스트 검증에서만 쓰는 기본 메서드
    long count();
}

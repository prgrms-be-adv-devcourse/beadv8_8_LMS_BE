package com.hapbang.chat.out;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatRecipientSet;

/**
 * 불변 수신자 집합 헤더 저장소. JPQL 조회는 {@link ChatRecipientSetRepositoryCustom}(QueryDSL)에 있다.
 * <p>
 * 조회 구현 조각({@code ...CustomImpl})도 같은 app 조회 인터페이스의 빈이다. app이 그 인터페이스로 주입받을 때 이 repository
 * 프록시(예외 변환 포함)가 뽑히도록 {@code @Primary}로 둔다.
 */
@Primary
public interface ChatRecipientSetRepository
        extends JpaRepository<ChatRecipientSet, Long>, ChatRecipientSetRepositoryCustom {
}

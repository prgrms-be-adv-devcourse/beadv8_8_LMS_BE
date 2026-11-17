package com.hapbang.chat.app;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatRoomLog;

/**
 * 방 이벤트 기록 저장소. JPQL 조회는 {@link ChatRoomLogRepositoryCustom}(QueryDSL)에 있다.
 */
public interface ChatRoomLogRepository extends Repository<ChatRoomLog, Long>, ChatRoomLogRepositoryCustom {

    // 기본 메서드: 이름과 시그니처가 같아 Spring Data 기본 구현(SimpleJpaRepository)이 처리한다
    <S extends ChatRoomLog> S save(S entity);

    /** 관리자 기록 조회 첫 페이지. (일어난 시각, ID) 순서 */
    List<ChatRoomLog> findByChatRoom_IdOrderByOccurredAtAscIdAsc(Long chatRoomId, Limit limit);
}

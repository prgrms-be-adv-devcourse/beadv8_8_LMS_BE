package com.hapbang.chat.out;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatRoomLog;

/**
 * 방 이벤트 기록 저장소. JPQL 조회는 {@link ChatRoomLogRepositoryCustom}(QueryDSL)에 있다.
 * <p>
 * 조회 구현 조각({@code ...CustomImpl})도 같은 app 조회 인터페이스의 빈이다. app이 그 인터페이스로 주입받을 때 이 repository
 * 프록시(예외 변환 포함)가 뽑히도록 {@code @Primary}로 둔다.
 */
@Primary
public interface ChatRoomLogRepository extends JpaRepository<ChatRoomLog, Long>, ChatRoomLogRepositoryCustom {

    List<ChatRoomLog> findByChatRoom_IdOrderByIdAsc(Long chatRoomId);

    /** 관리자 기록 조회 첫 페이지. (일어난 시각, ID) 순서 */
    List<ChatRoomLog> findByChatRoom_IdOrderByOccurredAtAscIdAsc(Long chatRoomId, Limit limit);
}

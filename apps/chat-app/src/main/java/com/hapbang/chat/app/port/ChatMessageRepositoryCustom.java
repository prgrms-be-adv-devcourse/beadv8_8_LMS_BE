package com.hapbang.chat.app.port;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;

import com.hapbang.chat.app.port.ChatMessageRows.MessageWithSender;
import com.hapbang.chat.domain.ChatMessage;

/**
 * {@link ChatMessageRepository}의 QueryDSL 조회. 구현은 out의 {@code ChatMessageRepositoryCustomImpl}이다. native SQL
 * 집계·쓰기와 이름으로 만드는 조회는 {@link ChatMessageRepository}에 둔다.
 */
public interface ChatMessageRepositoryCustom {

    /**
     * 참여자의 조회 범위([fromSequence, beforeSequence)) 안의 메시지를 최신순으로 조회한다.
     */
    List<ChatMessage> findVisible(Long chatRoomId, long fromSequence, long beforeSequence, Limit limit);

    /**
     * 회원이 참여한 방마다 조회 범위 안의 가장 최근 메시지. 볼 수 있는 메시지가 없는 방은 빠진다.
     */
    List<ChatMessage> findLastVisible(Long userId, Collection<Long> chatRoomIds);

    /**
     * 방마다 회원이 안 읽은 메시지 수. 조회 범위 안에서 마지막으로 읽은 순번 뒤에 온 상대(시스템 안내 포함) 메시지만 센다.
     * 안 읽은 메시지가 없는 방은 빠진다.
     */
    List<ChatRoomUnreadCount> countUnread(Long userId, Collection<Long> chatRoomIds);

    /**
     * 관리자 기록 조회 다음 페이지. 커서 시각 이후이거나, 같은 시각이면 {@code idAfter}보다 큰 메시지.
     * 행 비교로 써야 (chat_room_id, sent_at, id) 인덱스의 범위 조건이 되어 커서 앞 항목을 다시 훑지 않는다.
     */
    List<ChatMessage> findHistoryAfter(Long chatRoomId, LocalDateTime at, long idAfter, Limit limit);

    /**
     * 메시지와 발신자 복제본을 한 문장으로 읽는다(전달 원본). 발신자가 없거나 복제본이 없으면 발신자가 null이다.
     * 메시지가 없으면 빈 값이다. 전달 원본 조회({@code ChatDeliverySourceReader})가 쓴다.
     */
    Optional<MessageWithSender> findWithSender(long messageId);
}

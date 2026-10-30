package com.hapbang.chat.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatOutbox;

/**
 * 메시지 전달 outbox 저장·조회. 적재 상태 변경은 {@link JdbcChatOutboxStore}의 조건부 UPDATE로만 한다.
 */
public interface ChatOutboxRepository extends JpaRepository<ChatOutbox, Long> {

    Optional<ChatOutbox> findByDeliveryId(UUID deliveryId);

    List<ChatOutbox> findByChatRoomIdOrderByIdAsc(Long chatRoomId);

    Optional<ChatOutbox> findByMessageId(Long messageId);
}

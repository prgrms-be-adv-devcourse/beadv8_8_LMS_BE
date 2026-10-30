package com.hapbang.chat.out;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatControlOutbox;

/**
 * 연결 제어 outbox 조회. 기록은 {@link JdbcChatConnectionControl}, 적재 상태 변경은 {@link JdbcChatOutboxStore}가 한다.
 */
public interface ChatControlOutboxRepository extends JpaRepository<ChatControlOutbox, Long> {

    Optional<ChatControlOutbox> findByControlId(UUID controlId);
}

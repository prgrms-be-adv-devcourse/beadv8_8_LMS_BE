package com.hapbang.chat.app;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatOutbox;

/**
 * 메시지 전달 outbox 테스트 검증용 조회. 운영 코드가 쓰지 않는 조회라 port({@link ChatOutboxRepository})에 두지 않고 테스트 소스에 둔다.
 * 테스트 실행 때는 같은 패키지라 Spring Data가 함께 등록한다. 기본 메서드는 이름과 시그니처가 같아 기본 구현이 처리한다.
 */
public interface ChatOutboxTestRepository extends Repository<ChatOutbox, Long> {

    Optional<ChatOutbox> findByDeliveryId(UUID deliveryId);

    List<ChatOutbox> findByChatRoomIdOrderByIdAsc(Long chatRoomId);

    Optional<ChatOutbox> findByMessageId(Long messageId);
}

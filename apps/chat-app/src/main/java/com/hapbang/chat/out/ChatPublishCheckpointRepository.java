package com.hapbang.chat.out;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatPublishCheckpoint;

/**
 * 발행 체크포인트 저장·조회. 상태 변경은 {@link JdbcChatPublishCheckpointStore}의 조건부 UPDATE로만 한다.
 */
public interface ChatPublishCheckpointRepository extends JpaRepository<ChatPublishCheckpoint, Long> {

    Optional<ChatPublishCheckpoint> findByConsumerGroupAndJobId(String consumerGroup, UUID jobId);
}

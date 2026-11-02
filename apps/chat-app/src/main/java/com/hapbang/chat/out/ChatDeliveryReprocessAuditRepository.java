package com.hapbang.chat.out;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatDeliveryReprocessAudit;

/** 운영 재처리 감사 기록 조회. 기록은 {@link JpaChatOutboxStore#insertReprocessAudit}가 한다 */
public interface ChatDeliveryReprocessAuditRepository extends JpaRepository<ChatDeliveryReprocessAudit, Long> {

    List<ChatDeliveryReprocessAudit> findByConsumerGroupAndJobIdOrderByIdAsc(String consumerGroup, UUID jobId);
}

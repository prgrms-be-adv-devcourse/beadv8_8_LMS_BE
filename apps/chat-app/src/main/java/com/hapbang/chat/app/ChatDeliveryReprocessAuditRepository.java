package com.hapbang.chat.app;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatDeliveryReprocessAudit;

/** 운영 재처리 감사 기록 조회. 기록은 {@code JpaChatOutboxStore.insertReprocessAudit}가 한다 */
public interface ChatDeliveryReprocessAuditRepository extends Repository<ChatDeliveryReprocessAudit, Long> {

    // 테스트 검증에서만 쓰는 기본 메서드. 이름과 시그니처가 같아 Spring Data 기본 구현(SimpleJpaRepository)이 처리한다
    long count();

    List<ChatDeliveryReprocessAudit> findByConsumerGroupAndJobIdOrderByIdAsc(String consumerGroup, UUID jobId);
}

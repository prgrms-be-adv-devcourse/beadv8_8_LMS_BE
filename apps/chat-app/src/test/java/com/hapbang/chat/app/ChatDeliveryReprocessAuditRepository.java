package com.hapbang.chat.app;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatDeliveryReprocessAudit;

/**
 * 운영 재처리 감사 기록 조회(테스트 검증용). 기록은 {@link ChatOutboxStore#insertReprocessAudit}가 native 문장으로 한다. 운영 코드는 감사 기록을 읽지 않으므로 테스트
 * 소스에 둔다. 테스트는 {@code ModuleTestConfiguration}의 {@code @AutoConfigurationPackage("com.hapbang")}로 {@code com.hapbang}
 * 아래 테스트 소스도 스캔하므로 Spring Data가 함께 등록한다.
 */
public interface ChatDeliveryReprocessAuditRepository extends Repository<ChatDeliveryReprocessAudit, Long> {

    // 기본 메서드: 이름과 시그니처가 같아 Spring Data 기본 구현(SimpleJpaRepository)이 처리한다
    long count();

    List<ChatDeliveryReprocessAudit> findByConsumerGroupAndJobIdOrderByIdAsc(String consumerGroup, UUID jobId);
}

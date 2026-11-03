package com.hapbang.member.app;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Service;

/**
 * 회원 이벤트 Kafka 재발행 (#421, kafka.md "발행": 발행하는 앱마다 반드시 둔다). Kafka로 보내지 못한 회원 이벤트를 앱이 떠 있는
 * 동안 다시 보낸다.
 * <ul>
 *     <li>Modulith는 기동할 때만 미완료 기록을 다시 보낸다({@code republish-outstanding-events-on-restart}). broker가 producer
 *     재시도 시간보다 오래 멈췄다 돌아오면, 이 배치가 없을 때는 다음 배포까지 다른 앱의 회원 복제본과 지갑 생성이 밀린다.</li>
 *     <li>{@link ResubmissionOptions}로 <b>실패(FAILED)한 기록만</b> 다시 보낸다. 아직 전송 중(producer가 스스로 재시도 중)인 기록은
 *     건드리지 않아 같은 이벤트를 겹쳐 보내지 않는다. DB에서 실패한 기록을 {@code batchSize}건까지만 읽고, 다시 보내는 중인 기록이
 *     {@code batchSize}건이면 그 회차는 건너뛴다.</li>
 *     <li>발행한 지 {@code minAge}가 지난 기록만 고른다. 실제로 Kafka로 내보내는 이벤트인지는 외부화 설정
 *     ({@link EventExternalizationConfiguration#supports})으로 판단해 기준을 하나로 둔다.</li>
 *     <li>서버 여러 대가 같이 다시 보내 두 번 가거나 순서가 바뀌어도, 받는 쪽이 회원 버전({@code memberVersion})으로
 *     거른다(분산 락 없음, kafka.md).</li>
 * </ul>
 */
@Service
public class MemberEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final ResubmissionOptions options;

    public MemberEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
                                       EventExternalizationConfiguration externalization,
                                       @Value("${hapbang.member.event.republish-batch-size:500}") int batchSize,
                                       @Value("${hapbang.member.event.republish-min-age:1m}") Duration minAge) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.options = ResubmissionOptions.defaults()
                .withBatchSize(batchSize)
                .withMaxInFlight(batchSize)
                .withMinAge(minAge)
                .withFilter(publication -> externalization.supports(publication.getEvent()));
    }

    /** 실패한 회원 이벤트 발행 기록을 다시 보낸다. 보낼 것이 없으면 아무것도 하지 않는다 */
    public void republishFailed() {
        incompleteEventPublications.resubmitIncompletePublications(options);
    }

    ResubmissionOptions options() {
        return options;
    }
}

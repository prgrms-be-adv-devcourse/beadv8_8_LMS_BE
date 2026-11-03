package com.hapbang.member.app;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
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
 *     건드리지 않아 같은 이벤트를 겹쳐 보내지 않는다. 전송 중에 앱이 죽어 남은 기록은 재기동 때 보낸다.</li>
 *     <li>발행한 지 {@code minAge}가 지난 실패 기록을 DB에서 {@code batchSize}건까지만 읽고, 다시 보내는 중인 기록이
 *     {@code batchSize}건이면 그 회차는 건너뛴다.</li>
 *     <li>대상 필터는 두지 않는다. 회원 앱에는 같은 JVM 안에서 이벤트를 받는 리스너가 없어 실패 기록은 모두 Kafka 전송이다.
 *     Modulith는 필터를 DB에서 읽은 뒤에 적용하므로, 필터로 거르는 실패 기록이 {@code batchSize}건 넘게 쌓이면 회원 이벤트가
 *     영영 읽히지 않는다. 앱 안 리스너를 더하면 그 실패도 여기서 다시 실행되니, 그 리스너는 여러 번 실행돼도 괜찮게 만든다.</li>
 *     <li>서버 여러 대가 같이 다시 보내 두 번 가거나 순서가 바뀌어도, 받는 쪽이 회원 버전({@code memberVersion})으로
 *     거른다(분산 락 없음, kafka.md).</li>
 * </ul>
 */
@Service
public class MemberEventRepublishUseCase {

    private final IncompleteEventPublications incompleteEventPublications;
    private final ResubmissionOptions options;

    public MemberEventRepublishUseCase(IncompleteEventPublications incompleteEventPublications,
                                       @Value("${hapbang.member.event.republish-batch-size:500}") int batchSize,
                                       @Value("${hapbang.member.event.republish-min-age:1m}") Duration minAge) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.options = ResubmissionOptions.defaults()
                .withBatchSize(batchSize)
                .withMaxInFlight(batchSize)
                .withMinAge(minAge);
    }

    /** 실패한 회원 이벤트 발행 기록을 다시 보낸다. 보낼 것이 없으면 아무것도 하지 않는다 */
    public void republishFailed() {
        incompleteEventPublications.resubmitIncompletePublications(options);
    }

    ResubmissionOptions options() {
        return options;
    }
}

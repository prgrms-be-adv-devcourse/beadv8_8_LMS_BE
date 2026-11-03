package com.hapbang.member.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/**
 * 회원 이벤트 Kafka 재발행이 Modulith에 넘기는 조건 (#421). {@link ResubmissionOptions} 경로라 Modulith가 실패(FAILED)한 기록만
 * DB에서 건수를 제한해 읽는다. 실제로 Kafka에 다시 나가는지는 MemberEventKafkaRecoveryIntegrationTest가 확인한다
 */
class MemberEventRepublishUseCaseTest {

    private final IncompleteEventPublications incompleteEventPublications = mock(IncompleteEventPublications.class);
    private final MemberEventRepublishUseCase useCase =
            new MemberEventRepublishUseCase(incompleteEventPublications, 500, Duration.ofMinutes(1));

    @Test
    @DisplayName("실패한 기록 중 1분 지난 것을 한 번에 500건까지, 다시 보내는 중인 기록이 500건이면 쉬도록 넘긴다")
    void resubmitsFailedWithLimits() {
        useCase.republishFailed();

        verify(incompleteEventPublications).resubmitIncompletePublications(useCase.options());
        assertThat(useCase.options().getBatchSize()).isEqualTo(500);
        assertThat(useCase.options().getMaxInFlight()).isEqualTo(500);
        assertThat(useCase.options().getMinAge()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("대상 필터를 두지 않는다: DB에서 읽은 실패 기록을 모두 다시 보내, 걸러진 기록이 쌓여 회원 이벤트가 굶는 일이 없다")
    void noFilter() {
        assertThat(useCase.options().getFilter().test(mock(EventPublication.class))).isTrue();
    }
}

package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.shared.appearance.domain.AppearanceResult;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.event.AppearanceChangedEvent;

/**
 * 출연 변경 이벤트(#713)가 모든 상태 전이에서 바뀐 뒤 상태로 한 번씩 발행되는지 확인한다.
 * <ul>
 *     <li>발행만 기록해서 본다({@link RecordApplicationEvents}). Kafka key·헤더는 {@code AppearanceEventKafkaTest}가 본다.</li>
 *     <li>실린 version이 커밋된 행의 version과 같아야 받는 쪽이 "오래됨"으로 버리지 않는다. 그래서 매번 동기화 목록
 *     ({@link AppearanceSyncQueryUseCase}, 내부 API와 같은 값)과 통째로 비교한다. 같으면 이벤트와 내부 API 값도 같다.</li>
 * </ul>
 */
@AppearanceIntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "INFLUENCER")
class AppearanceChangedEventTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long OTHER = 4L;

    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceCancelAutoRejectUseCase appearanceCancelAutoRejectUseCase;

    @Autowired
    AppearanceAutoConfirmUseCase appearanceAutoConfirmUseCase;

    @Autowired
    AppearanceSyncQueryUseCase appearanceSyncQueryUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        for (Long id : List.of(HOST, GUEST, GUEST2, OTHER)) {
            appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(id));
        }
        applicationEvents.clear();
    }

    /** 요청서를 보내고 게스트가 수락해 성립한 출연 ID */
    private Long establishFromRequest() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    private Long postRecruit() {
        return appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())).id();
    }

    /** 게스트가 지원하고 호스트가 수락해 성립한 출연 ID */
    private Long establishFromRecruit(Long recruitId, Long guestId) {
        Long applicationId = appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
        return appearanceApplicationFacade.accept(HOST, applicationId).appearanceId();
    }

    /** 방송이 끝나 확인 기간에 들어선다 (방송 예정 일시 1시간 뒤) */
    private void broadcastEnded(Long appearanceId) {
        clock.advanceTo(scheduledAtOf(appearanceId).plusHours(1));
    }

    private LocalDateTime scheduledAtOf(Long appearanceId) {
        return jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    /** 이 출연에 대해 발행된 변경 이벤트 (발행 순서) */
    private List<AppearanceSyncDto> changes(Long appearanceId) {
        return applicationEvents.stream(AppearanceChangedEvent.class)
                .map(AppearanceChangedEvent::appearance)
                .filter(appearance -> appearance.id().equals(appearanceId))
                .toList();
    }

    /** 커밋된 출연을 동기화 내부 API와 같은 방법으로 읽은 값 */
    private AppearanceSyncDto committed(Long appearanceId) {
        List<AppearanceSyncDto> items = appearanceSyncQueryUseCase.findAppearances(appearanceId - 1, 1).items();
        assertThat(items).extracting(AppearanceSyncDto::id).containsExactly(appearanceId);
        return items.getFirst();
    }

    /** 전이 하나로 이벤트가 정확히 하나 더 나갔고, 그 값이 커밋된 값과 같다 */
    private AppearanceSyncDto assertOneMoreChange(Long appearanceId, int before, AppearanceResult result) {
        List<AppearanceSyncDto> changes = changes(appearanceId);
        assertThat(changes).hasSize(before + 1);
        AppearanceSyncDto last = changes.getLast();
        assertThat(last.result()).isEqualTo(result);
        assertThat(last).isEqualTo(committed(appearanceId));
        return last;
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("성립")
    class Establish {

        @Test
        @DisplayName("요청 수락: SCHEDULED, version 0, 호스트·게스트·방송 예정 일시를 담는다")
        void fromRequest() {
            Long appearanceId = establishFromRequest();

            AppearanceSyncDto changed = assertOneMoreChange(appearanceId, 0, AppearanceResult.SCHEDULED);
            assertThat(changed.version()).isZero();
            assertThat(changed.hostId()).isEqualTo(HOST);
            assertThat(changed.guestId()).isEqualTo(GUEST);
            assertThat(changed.scheduledAt()).isEqualTo(scheduledAtOf(appearanceId));
        }

        @Test
        @DisplayName("모집 지원 수락: 성립한 출연마다 SCHEDULED, version 0")
        void fromRecruit() {
            Long recruitId = postRecruit();
            Long first = establishFromRecruit(recruitId, GUEST);
            Long second = establishFromRecruit(recruitId, GUEST2);

            assertThat(assertOneMoreChange(first, 0, AppearanceResult.SCHEDULED).guestId()).isEqualTo(GUEST);
            assertThat(assertOneMoreChange(second, 0, AppearanceResult.SCHEDULED).guestId()).isEqualTo(GUEST2);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 취소 요청")
    class CancelRequest {

        @Test
        @DisplayName("취소 요청: 공개 결과는 SCHEDULED 그대로지만 version이 올라 나간다")
        void request() {
            Long appearanceId = establishFromRequest();

            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");

            assertThat(assertOneMoreChange(appearanceId, 1, AppearanceResult.SCHEDULED).version()).isEqualTo(1L);
        }

        @Test
        @DisplayName("호스트 거절: 다시 성립 상태로 SCHEDULED, version이 또 오른다")
        void reject() {
            Long appearanceId = establishFromRequest();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");

            appearanceFacade.rejectCancel(HOST, appearanceId, null);

            assertThat(assertOneMoreChange(appearanceId, 2, AppearanceResult.SCHEDULED).version()).isEqualTo(2L);
        }

        @Test
        @DisplayName("방송 예정 일시 자동 거절 배치: SCHEDULED")
        void autoReject() {
            Long appearanceId = establishFromRequest();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");
            broadcastEnded(appearanceId);

            assertThat(appearanceCancelAutoRejectUseCase.rejectDue()).isEqualTo(1);

            assertOneMoreChange(appearanceId, 2, AppearanceResult.SCHEDULED);
        }

        @Test
        @DisplayName("합의 취소(호스트 수락): CANCELED")
        void agree() {
            Long appearanceId = establishFromRequest();
            appearanceFacade.requestCancel(GUEST, appearanceId, "일정이 생겼습니다.");

            appearanceFacade.acceptCancel(HOST, appearanceId);

            assertOneMoreChange(appearanceId, 2, AppearanceResult.CANCELED);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 확인·불참")
    class Confirm {

        @Test
        @DisplayName("호스트 확인: COMPLETED")
        void confirm() {
            Long appearanceId = establishFromRequest();
            broadcastEnded(appearanceId);

            appearanceFacade.confirm(HOST, appearanceId);

            assertOneMoreChange(appearanceId, 1, AppearanceResult.COMPLETED);
        }

        @Test
        @DisplayName("불참: NO_SHOW")
        void absent() {
            Long appearanceId = establishFromRequest();
            broadcastEnded(appearanceId);

            appearanceFacade.markAbsent(HOST, appearanceId, null);

            assertOneMoreChange(appearanceId, 1, AppearanceResult.NO_SHOW);
        }

        @Test
        @DisplayName("D+7 자동 확인 배치: COMPLETED")
        void autoConfirm() {
            Long appearanceId = establishFromRequest();
            clock.advanceTo(scheduledAtOf(appearanceId).plusDays(7));

            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isEqualTo(1);

            assertOneMoreChange(appearanceId, 1, AppearanceResult.COMPLETED);
        }

        @Test
        @DisplayName("전이가 막히면(호스트가 아닌 회원의 확인) 이벤트가 나가지 않는다")
        void notOnFailure() {
            Long appearanceId = establishFromRequest();
            broadcastEnded(appearanceId);

            assertThatThrownBy(() -> appearanceFacade.confirm(OTHER, appearanceId))
                    .isInstanceOf(AppearanceException.class)
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_NOT_FOUND);

            assertThat(changes(appearanceId)).hasSize(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("호스트 일방 취소")
    class HostCancel {

        @Test
        @DisplayName("출연 하나 취소: CANCELED")
        void single() {
            Long appearanceId = establishFromRequest();

            appearanceFacade.hostCancel(HOST, appearanceId, false, "방송이 취소됐습니다.");

            assertOneMoreChange(appearanceId, 1, AppearanceResult.CANCELED);
        }

        @Test
        @DisplayName("모집 전체 취소: 함께 취소된 출연마다 CANCELED가 하나씩, 커밋된 version으로 나간다")
        void wholeRecruit() {
            Long recruitId = postRecruit();
            Long first = establishFromRecruit(recruitId, GUEST);
            Long second = establishFromRecruit(recruitId, GUEST2);

            appearanceRecruitFacade.cancel(HOST, recruitId, false, "방송이 취소됐습니다.");

            assertOneMoreChange(first, 1, AppearanceResult.CANCELED);
            assertOneMoreChange(second, 1, AppearanceResult.CANCELED);
        }
    }
}

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
import com.hapbang.shared.appearance.domain.AppearanceConfirmType;
import com.hapbang.shared.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceDto;
import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationNotSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCanceledByHostEvent;
import com.hapbang.shared.appearance.event.AppearanceConfirmedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitClosedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitOpenedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestExpiredEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestRejectedEvent;

/**
 * 알림용 출연 이벤트 12종(#871)이 상태 전이마다 알림 계약의 값으로 한 번씩 발행되는지 확인한다.
 * <ul>
 *     <li>발행만 기록해서 본다({@link RecordApplicationEvents}). Kafka key·헤더는 {@code AppearanceEventKafkaTest}가 본다.</li>
 *     <li>예약 짝 키(대상 ID, 받는 사람 ID)가 만드는 이벤트와 지우는 이벤트에 같이 실리는지 값으로 확인한다.</li>
 *     <li>사건 시각은 처리 중의 현재 시각이라 처리 전후 시각 사이인지만 본다.</li>
 * </ul>
 */
@AppearanceIntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "INFLUENCER")
class AppearanceNotificationEventTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final Long GUEST2 = 3L;
    private static final Long OTHER = 4L;
    private static final String REQUEST_TITLE = "주말 게임 합방 출연 요청";
    private static final String RECRUIT_TITLE = "주말 게임 합방 게스트 모집";
    /** 출연료 30,000 HP의 위약금 30% */
    private static final long PENALTY = 9_000L;

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
    AppearanceRequestExpireUseCase appearanceRequestExpireUseCase;

    @Autowired
    AppearanceRecruitDeadlineCloseUseCase appearanceRecruitDeadlineCloseUseCase;

    @Autowired
    AppearanceRecruitHoldUseCase appearanceRecruitHoldUseCase;

    @Autowired
    AppearanceAutoConfirmUseCase appearanceAutoConfirmUseCase;

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

    private AppearanceRequestResDto sendRequest() {
        return appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(clock.now()));
    }

    private Long postRecruit() {
        return appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(clock.now())).id();
    }

    private Long apply(Long recruitId, Long guestId) {
        return appearanceApplicationFacade.apply(guestId, recruitId, "지원합니다.").id();
    }

    /** 요청서를 보내고 게스트가 수락해 성립한 출연 ID */
    private Long establishFromRequest() {
        AppearanceRequestResDto sent = sendRequest();
        return appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
    }

    private LocalDateTime scheduledAtOf(Long appearanceId) {
        return jdbcTemplate.queryForObject("select scheduled_at from appearances.appearances where id = ?",
                LocalDateTime.class, appearanceId);
    }

    private String sourceTitleOf(Long appearanceId, String taskType) {
        return jdbcTemplate.queryForObject(
                "select source_title from appearances.hp_tasks where appearance_id = ? and task_type = ?",
                String.class, appearanceId, taskType);
    }

    private <T> T only(Class<T> type) {
        List<T> events = applicationEvents.stream(type).toList();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private static void assertRequest(AppearanceRequestDto request, Long requestId) {
        assertThat(request.requestId()).isEqualTo(requestId);
        assertThat(request.hostId()).isEqualTo(HOST);
        assertThat(request.guestId()).isEqualTo(GUEST);
        assertThat(request.title()).isEqualTo(REQUEST_TITLE);
        assertThat(request.fee()).isEqualTo(AppearanceRequestFixture.FEE);
    }

    private static void assertAppearance(AppearanceDto appearance, Long appearanceId, String title, Long guestId) {
        assertThat(appearance.appearanceId()).isEqualTo(appearanceId);
        assertThat(appearance.title()).isEqualTo(title);
        assertThat(appearance.hostId()).isEqualTo(HOST);
        assertThat(appearance.guestId()).isEqualTo(guestId);
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연 요청서")
    class Request {

        @Test
        @DisplayName("수락: 요청서·출연 ID·사건 시각을 담아 한 번. 출연 예약을 만들고 요청 만료 임박 예약을 지우는 키(요청서·게스트·출연 ID)가 있다")
        void accepted() {
            AppearanceRequestResDto sent = sendRequest();
            LocalDateTime before = clock.now();

            Long appearanceId = appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();

            AppearanceRequestAcceptedEvent event = only(AppearanceRequestAcceptedEvent.class);
            assertRequest(event.request(), sent.id());
            assertThat(event.request().scheduledAt()).isEqualTo(scheduledAtOf(appearanceId));
            assertThat(event.appearanceId()).isEqualTo(appearanceId);
            assertThat(event.acceptedAt()).isBetween(before, clock.now());
            assertThat(applicationEvents.stream(AppearanceRequestRejectedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("거절: 요청서와 거절 시각. 수락 이벤트는 없다")
        void rejected() {
            AppearanceRequestResDto sent = sendRequest();
            LocalDateTime before = clock.now();

            appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");

            AppearanceRequestRejectedEvent event = only(AppearanceRequestRejectedEvent.class);
            assertRequest(event.request(), sent.id());
            assertThat(event.rejectedAt()).isBetween(before, clock.now());
            assertThat(applicationEvents.stream(AppearanceRequestAcceptedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("응답 전 호스트 취소: 요청서와 취소 시각")
        void canceled() {
            AppearanceRequestResDto sent = sendRequest();
            LocalDateTime before = clock.now();

            appearanceRequestFacade.cancel(HOST, sent.id());

            AppearanceRequestCanceledEvent event = only(AppearanceRequestCanceledEvent.class);
            assertRequest(event.request(), sent.id());
            assertThat(event.canceledAt()).isBetween(before, clock.now());
        }

        @Test
        @DisplayName("수락 기한 만료 배치: 요청서와 만료 처리 시각(수락 기한 뒤)")
        void expired() {
            AppearanceRequestResDto sent = sendRequest();
            clock.advanceTo(sent.acceptDeadline().plusMinutes(1));
            LocalDateTime before = clock.now();

            assertThat(appearanceRequestExpireUseCase.expireDue()).isEqualTo(1);

            AppearanceRequestExpiredEvent event = only(AppearanceRequestExpiredEvent.class);
            assertRequest(event.request(), sent.id());
            assertThat(event.expiredAt()).isBetween(before, clock.now()).isAfter(sent.acceptDeadline());
        }

        @Test
        @DisplayName("전이가 막히면(게스트가 아닌 회원의 거절) 이벤트가 나가지 않는다")
        void notOnFailure() {
            AppearanceRequestResDto sent = sendRequest();

            assertThatThrownBy(() -> appearanceRequestFacade.reject(OTHER, sent.id(), null))
                    .isInstanceOf(AppearanceException.class);

            assertThat(applicationEvents.stream(AppearanceRequestRejectedEvent.class)).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("모집 지원")
    class Application {

        @Test
        @DisplayName("수락: 지원(모집글 제목·호스트·게스트)·출연 ID·방송 예정 일시·선정 시각")
        void selected() {
            Long recruitId = postRecruit();
            Long applicationId = apply(recruitId, GUEST);
            LocalDateTime before = clock.now();

            Long appearanceId = appearanceApplicationFacade.accept(HOST, applicationId).appearanceId();

            AppearanceApplicationSelectedEvent event = only(AppearanceApplicationSelectedEvent.class);
            assertApplication(event.application(), applicationId, recruitId, GUEST);
            assertThat(event.appearanceId()).isEqualTo(appearanceId);
            assertThat(event.scheduledAt()).isEqualTo(scheduledAtOf(appearanceId));
            assertThat(event.selectedAt()).isBetween(before, clock.now());
            assertThat(applicationEvents.stream(AppearanceApplicationNotSelectedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("거절: 미선정 이벤트")
        void rejected() {
            Long recruitId = postRecruit();
            Long applicationId = apply(recruitId, GUEST);
            LocalDateTime before = clock.now();

            appearanceApplicationFacade.reject(HOST, applicationId);

            AppearanceApplicationNotSelectedEvent event = only(AppearanceApplicationNotSelectedEvent.class);
            assertApplication(event.application(), applicationId, recruitId, GUEST);
            assertThat(event.notSelectedAt()).isBetween(before, clock.now());
            assertThat(applicationEvents.stream(AppearanceApplicationSelectedEvent.class)).isEmpty();
        }

        private void assertApplication(AppearanceApplicationDto application, Long applicationId, Long recruitId,
                                       Long guestId) {
            assertThat(application.applicationId()).isEqualTo(applicationId);
            assertThat(application.recruitId()).isEqualTo(recruitId);
            assertThat(application.recruitTitle()).isEqualTo(RECRUIT_TITLE);
            assertThat(application.hostId()).isEqualTo(HOST);
            assertThat(application.guestId()).isEqualTo(guestId);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("모집글")
    class Recruit {

        @Test
        @DisplayName("올리기: 보류가 끝나면 모집 시작 한 번(모집 마감 임박 예약 키: 모집글·호스트·마감 일시). 같은 결과를 다시 반영해도 더 나가지 않는다")
        void opened() {
            LocalDateTime before = clock.now();
            Long recruitId = postRecruit();

            AppearanceRecruitOpenedEvent event = only(AppearanceRecruitOpenedEvent.class);
            assertRecruit(event.recruit(), recruitId);
            assertThat(event.recruit().recruitDeadline()).isEqualTo(jdbcTemplate.queryForObject(
                    "select recruit_deadline from appearances.recruits where id = ?", LocalDateTime.class, recruitId));
            assertThat(event.openedAt()).isBetween(before, clock.now());

            // 보류 재확인 배치가 같은 보류 결과를 다시 반영한 경우
            appearanceRecruitHoldUseCase.markRecruiting(recruitId);

            assertThat(applicationEvents.stream(AppearanceRecruitOpenedEvent.class)).hasSize(1);
        }

        @Test
        @DisplayName("조기 마감: 마감(EARLY)과 대기 중인 지원마다 미선정. 수락된 지원은 미선정이 아니다")
        void closedEarly() {
            Long recruitId = postRecruit();
            appearanceApplicationFacade.accept(HOST, apply(recruitId, GUEST));
            Long pendingId = apply(recruitId, GUEST2);
            LocalDateTime before = clock.now();

            appearanceRecruitFacade.closeEarly(HOST, recruitId);

            AppearanceRecruitClosedEvent closed = only(AppearanceRecruitClosedEvent.class);
            assertRecruit(closed.recruit(), recruitId);
            assertThat(closed.closeType()).isEqualTo(AppearanceRecruitCloseType.EARLY);
            assertThat(closed.closedAt()).isBetween(before, clock.now());
            AppearanceApplicationNotSelectedEvent notSelected = only(AppearanceApplicationNotSelectedEvent.class);
            assertThat(notSelected.application().applicationId()).isEqualTo(pendingId);
            assertThat(notSelected.application().guestId()).isEqualTo(GUEST2);
            assertThat(notSelected.notSelectedAt()).isEqualTo(closed.closedAt());
        }

        @Test
        @DisplayName("기한 마감 배치: 마감(DEADLINE), 마감 시각은 마감 일시 뒤")
        void closedByDeadline() {
            Long recruitId = postRecruit();
            AppearanceRecruitDto opened = only(AppearanceRecruitOpenedEvent.class).recruit();
            clock.advanceTo(opened.recruitDeadline().plusMinutes(1));

            assertThat(appearanceRecruitDeadlineCloseUseCase.closeDue()).isEqualTo(1);

            AppearanceRecruitClosedEvent closed = only(AppearanceRecruitClosedEvent.class);
            assertRecruit(closed.recruit(), recruitId);
            assertThat(closed.closeType()).isEqualTo(AppearanceRecruitCloseType.DEADLINE);
            assertThat(closed.closedAt()).isAfter(opened.recruitDeadline());
        }

        @Test
        @DisplayName("모집 중 전체 취소: 모집 취소 한 번, 성립한 출연마다 호스트 일방 취소(모집글 제목·위약금), 대기 지원마다 미선정. 마감 이벤트는 없다")
        void canceledWhileRecruiting() {
            Long recruitId = postRecruit();
            Long appearanceId = appearanceApplicationFacade.accept(HOST, apply(recruitId, GUEST)).appearanceId();
            Long pendingId = apply(recruitId, GUEST2);
            LocalDateTime before = clock.now();

            appearanceRecruitFacade.cancel(HOST, recruitId, false, "방송이 취소됐습니다.");

            AppearanceRecruitCanceledEvent canceled = only(AppearanceRecruitCanceledEvent.class);
            assertRecruit(canceled.recruit(), recruitId);
            assertThat(canceled.canceledAt()).isBetween(before, clock.now());
            AppearanceCanceledByHostEvent hostCanceled = only(AppearanceCanceledByHostEvent.class);
            assertAppearance(hostCanceled.appearance(), appearanceId, RECRUIT_TITLE, GUEST);
            assertThat(hostCanceled.penaltyAmount()).isEqualTo(PENALTY);
            assertThat(only(AppearanceApplicationNotSelectedEvent.class).application().applicationId())
                    .isEqualTo(pendingId);
            assertThat(applicationEvents.stream(AppearanceRecruitClosedEvent.class)).isEmpty();
            assertThat(sourceTitleOf(appearanceId, "PENALTY")).isEqualTo(RECRUIT_TITLE);
        }

        @Test
        @DisplayName("마감 뒤 전체 취소: 미선정은 마감 때 이미 나갔으므로 다시 나가지 않는다")
        void canceledAfterClose() {
            Long recruitId = postRecruit();
            appearanceApplicationFacade.accept(HOST, apply(recruitId, GUEST));
            apply(recruitId, GUEST2);
            appearanceRecruitFacade.closeEarly(HOST, recruitId);

            appearanceRecruitFacade.cancel(HOST, recruitId, false, "방송이 취소됐습니다.");

            assertThat(applicationEvents.stream(AppearanceRecruitCanceledEvent.class)).hasSize(1);
            assertThat(applicationEvents.stream(AppearanceCanceledByHostEvent.class)).hasSize(1);
            assertThat(applicationEvents.stream(AppearanceApplicationNotSelectedEvent.class)).hasSize(1);
        }

        private void assertRecruit(AppearanceRecruitDto recruit, Long recruitId) {
            assertThat(recruit.recruitId()).isEqualTo(recruitId);
            assertThat(recruit.hostId()).isEqualTo(HOST);
            assertThat(recruit.title()).isEqualTo(RECRUIT_TITLE);
            assertThat(recruit.recruitDeadline()).isNotNull();
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("출연")
    class Appearance {

        @Test
        @DisplayName("호스트 일방 취소: 출연(요청서 제목)·위약금·취소 시각. 위약금 작업에 출처 제목을 남긴다")
        void canceledByHost() {
            Long appearanceId = establishFromRequest();
            LocalDateTime before = clock.now();

            appearanceFacade.hostCancel(HOST, appearanceId, false, "방송이 취소됐습니다.");

            AppearanceCanceledByHostEvent event = only(AppearanceCanceledByHostEvent.class);
            assertAppearance(event.appearance(), appearanceId, REQUEST_TITLE, GUEST);
            assertThat(event.appearance().scheduledAt()).isEqualTo(scheduledAtOf(appearanceId));
            assertThat(event.penaltyAmount()).isEqualTo(PENALTY);
            assertThat(event.canceledAt()).isBetween(before, clock.now());
            assertThat(sourceTitleOf(appearanceId, "PENALTY")).isEqualTo(REQUEST_TITLE);
        }

        @Test
        @DisplayName("호스트 확인: 확인(HOST). 지급 작업에 출처 제목을 남긴다")
        void confirmedByHost() {
            Long appearanceId = establishFromRequest();
            clock.advanceTo(scheduledAtOf(appearanceId).plusHours(1));
            LocalDateTime before = clock.now();

            appearanceFacade.confirm(HOST, appearanceId);

            AppearanceConfirmedEvent event = only(AppearanceConfirmedEvent.class);
            assertAppearance(event.appearance(), appearanceId, REQUEST_TITLE, GUEST);
            assertThat(event.confirmType()).isEqualTo(AppearanceConfirmType.HOST);
            assertThat(event.confirmedAt()).isBetween(before, clock.now());
            assertThat(sourceTitleOf(appearanceId, "PAYOUT")).isEqualTo(REQUEST_TITLE);
            assertThat(applicationEvents.stream(AppearanceAbsentEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("불참: 출연과 불참 처리 시각. 확인 이벤트는 없다")
        void absent() {
            Long appearanceId = establishFromRequest();
            clock.advanceTo(scheduledAtOf(appearanceId).plusHours(1));
            LocalDateTime before = clock.now();

            appearanceFacade.markAbsent(HOST, appearanceId, null);

            AppearanceAbsentEvent event = only(AppearanceAbsentEvent.class);
            assertAppearance(event.appearance(), appearanceId, REQUEST_TITLE, GUEST);
            assertThat(event.absentAt()).isBetween(before, clock.now());
            assertThat(applicationEvents.stream(AppearanceConfirmedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("D+7 자동 확인 배치: 출연마다 확인(AUTO)과 출처 제목(요청서·모집글 출처가 섞여도 각자의 제목)")
        void confirmedAutomatically() {
            Long fromRequest = establishFromRequest();
            Long recruitId = postRecruit();
            Long fromRecruit = appearanceApplicationFacade.accept(HOST, apply(recruitId, GUEST2)).appearanceId();
            clock.advanceTo(scheduledAtOf(fromRecruit).plusDays(7));

            assertThat(appearanceAutoConfirmUseCase.confirmDue()).isEqualTo(2);

            List<AppearanceConfirmedEvent> events = applicationEvents.stream(AppearanceConfirmedEvent.class).toList();
            assertThat(events).extracting(AppearanceConfirmedEvent::confirmType)
                    .containsOnly(AppearanceConfirmType.AUTO);
            assertThat(events).extracting(event -> event.appearance().appearanceId(), event -> event.appearance().title())
                    .containsExactlyInAnyOrder(tuple(fromRequest, REQUEST_TITLE), tuple(fromRecruit, RECRUIT_TITLE));
            assertThat(sourceTitleOf(fromRequest, "PAYOUT")).isEqualTo(REQUEST_TITLE);
            assertThat(sourceTitleOf(fromRecruit, "PAYOUT")).isEqualTo(RECRUIT_TITLE);
        }

        @Test
        @DisplayName("전이가 막히면(호스트가 아닌 회원의 확인) 이벤트가 나가지 않는다")
        void notOnFailure() {
            Long appearanceId = establishFromRequest();
            clock.advanceTo(scheduledAtOf(appearanceId).plusHours(1));

            assertThatThrownBy(() -> appearanceFacade.confirm(OTHER, appearanceId))
                    .isInstanceOf(AppearanceException.class)
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.APPEARANCE_NOT_FOUND);

            assertThat(applicationEvents.stream(AppearanceConfirmedEvent.class)).isEmpty();
        }
    }
}

package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.AppearanceRequestRepository;
import com.hapbang.appearance.out.billing.FakeBillingClient;
import com.hapbang.global.exception.BusinessException;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.testsupport.IntegrationTest;

/**
 * 출연 알림 이벤트(요청 도착·지원 도착)가 언제, 몇 번, 무엇을 담아 발행되는지 확인한다 (ISSUE.md APR-29).
 * 받는 쪽(채팅)은 이 모듈에 없으므로 발행만 기록해서 본다 ({@link RecordApplicationEvents}).
 */
@IntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "INFLUENCER")
class AppearanceEventPublishTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;

    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceApplicationFacade appearanceApplicationFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    AppearanceHoldRecheckUseCase appearanceHoldRecheckUseCase;

    @Autowired
    AppearanceRequestHoldUseCase appearanceRequestHoldUseCase;

    @Autowired
    AppearanceRequestRepository appearanceRequestRepository;

    @Autowired
    FakeBillingClient fakeBillingClient;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
        applicationEvents.clear();
    }

    @AfterEach
    void tearDown() {
        fakeBillingClient.reset();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private List<AppearanceRequestSentEvent> requestSentEvents() {
        return applicationEvents.stream(AppearanceRequestSentEvent.class).toList();
    }

    private List<AppearanceApplicationSubmittedEvent> submittedEvents() {
        return applicationEvents.stream(AppearanceApplicationSubmittedEvent.class).toList();
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("요청 도착 (AppearanceRequestSentEvent)")
    class RequestSent {

        @Test
        @DisplayName("보내기가 성공하면(REQUESTED) 한 번 발행하고, 요청서 ID·호스트·게스트·제목·출연료·방송 예정 일시·수락 기한을 담는다")
        void sent() {
            AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                    AppearanceRequestFixture.content(now()));

            assertThat(requestSentEvents()).hasSize(1);
            AppearanceRequestDto request = requestSentEvents().getFirst().request();
            assertThat(request.requestId()).isEqualTo(sent.id());
            assertThat(request.hostId()).isEqualTo(HOST);
            assertThat(request.guestId()).isEqualTo(GUEST);
            assertThat(request.title()).isEqualTo(sent.title());
            assertThat(request.fee()).isEqualTo(AppearanceRequestFixture.FEE);
            assertThat(request.scheduledAt()).isEqualTo(sent.scheduledAt());
            assertThat(request.acceptDeadline()).isEqualTo(sent.acceptDeadline());
        }

        @Test
        @DisplayName("Billing이 보류를 거절하면(HOLD_FAILED) 발행하지 않는다")
        void holdRejected() {
            fakeBillingClient.setBalance(HOST, 0);

            assertThatThrownBy(() -> appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(now())))
                    .isInstanceOf(BusinessException.class);

            assertThat(requestSentEvents()).isEmpty();
        }

        @Test
        @DisplayName("보류 결과를 모르면(HOLD_PENDING) 발행하지 않고, 보류 재확인 배치가 REQUESTED로 정리할 때 한 번 발행한다")
        void holdPendingThenRecheck() {
            fakeBillingClient.setUnavailable(true);
            assertThatThrownBy(() -> appearanceRequestFacade.create(HOST, GUEST, AppearanceRequestFixture.content(now())))
                    .isInstanceOf(AppearanceException.class)
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.BILLING_UNAVAILABLE);
            fakeBillingClient.setUnavailable(false);
            assertThat(requestSentEvents()).isEmpty();

            Long requestId = appearanceRequestRepository.findAll().getFirst().getId();
            jdbcTemplate.update("update appearances.requests set created_at = ? where id = ?",
                    now().minusMinutes(2), requestId);
            appearanceHoldRecheckUseCase.recheckDue();

            assertThat(requestSentEvents()).hasSize(1);
            assertThat(requestSentEvents().getFirst().request().requestId()).isEqualTo(requestId);
        }

        @Test
        @DisplayName("이미 REQUESTED인 요청서에 보류 성공을 다시 반영하면(보내기와 재확인이 겹침) 다시 발행하지 않는다")
        void notTwice() {
            AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                    AppearanceRequestFixture.content(now()));

            appearanceRequestHoldUseCase.markRequested(sent.id());

            assertThat(requestSentEvents()).hasSize(1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("지원 도착 (AppearanceApplicationSubmittedEvent)")
    class ApplicationSubmitted {

        @Test
        @DisplayName("지원하면 한 번 발행하고, 지원 ID·모집글 ID·모집글 제목·호스트·게스트·지원 시각을 담는다")
        void submitted() {
            AppearanceRecruitResDto recruit = appearanceRecruitFacade.create(HOST,
                    AppearanceRecruitFixture.content(now()));

            AppearanceApplicationResDto applied = appearanceApplicationFacade.apply(GUEST, recruit.id(), "지원합니다.");

            assertThat(submittedEvents()).hasSize(1);
            AppearanceApplicationDto application = submittedEvents().getFirst().application();
            assertThat(application.applicationId()).isEqualTo(applied.id());
            assertThat(application.recruitId()).isEqualTo(recruit.id());
            assertThat(application.recruitTitle()).isEqualTo(recruit.title());
            assertThat(application.hostId()).isEqualTo(HOST);
            assertThat(application.guestId()).isEqualTo(GUEST);
            assertThat(application.appliedAt()).isNotNull();
        }

        @Test
        @DisplayName("지원이 막히면(다시 지원·자기 모집글) 발행하지 않는다")
        void notOnFailure() {
            AppearanceRecruitResDto recruit = appearanceRecruitFacade.create(HOST,
                    AppearanceRecruitFixture.content(now()));
            appearanceApplicationFacade.apply(GUEST, recruit.id(), "지원합니다.");

            assertThatThrownBy(() -> appearanceApplicationFacade.apply(GUEST, recruit.id(), "다시"))
                    .isInstanceOf(AppearanceException.class)
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.DUPLICATE_APPLICATION);
            assertThatThrownBy(() -> appearanceApplicationFacade.apply(HOST, recruit.id(), "내 글"))
                    .isInstanceOf(AppearanceException.class)
                    .extracting("errorCode").isEqualTo(AppearanceErrorCode.CANNOT_APPLY_OWN_RECRUIT);

            assertThat(submittedEvents()).hasSize(1);
        }

        @Test
        @DisplayName("모집글을 올리는 것만으로는 어떤 알림 이벤트도 발행하지 않는다")
        void notOnRecruitCreate() {
            appearanceRecruitFacade.create(HOST, AppearanceRecruitFixture.content(now()));

            assertThat(submittedEvents()).isEmpty();
            assertThat(requestSentEvents()).isEmpty();
        }
    }
}

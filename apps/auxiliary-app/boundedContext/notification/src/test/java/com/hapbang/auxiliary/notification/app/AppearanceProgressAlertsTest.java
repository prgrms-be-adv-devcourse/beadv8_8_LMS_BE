package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
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
import com.hapbang.shared.appearance.event.AppearanceRecruitClosedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestExpiredEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestRejectedEvent;

/**
 * 출연 진행 알림 10종의 초안과 문구(N7 출연, NT-GEN-10·15·20). 받는 사람, 원본 대상 ID(ND-110), 이동 대상(ND-111),
 * 문구(ND-112, 알림 종류 0.8), 보내지 않는 경우, 필수 값 검증(ND-086과 같은 방식).
 */
class AppearanceProgressAlertsTest {

    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final long REQUEST_ID = 11L;
    private static final long APPLICATION_ID = 21L;
    private static final long RECRUIT_ID = 31L;
    private static final long APPEARANCE_ID = 41L;
    /** KST 2026-11-11 10:00 = UTC 01:00 */
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 11, 10, 0);
    private static final Instant AT_INSTANT = Instant.parse("2026-11-11T01:00:00Z");
    private static final LocalDateTime SCHEDULED = LocalDateTime.of(2026, 12, 1, 20, 0);

    @Test
    void 요청_수락은_호스트에게_성립한_출연으로_이동하고_원본_대상은_요청서다() {
        NotificationDraft draft = AppearanceProgressAlerts.requestAccepted(
                new AppearanceRequestAcceptedEvent(request(), APPEARANCE_ID, AT), "게스트닉");

        assertThat(draft.receiverUserId()).isEqualTo(HOST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_REQUEST_ACCEPTED");
        assertThat(draft.sourceEventType()).isEqualTo("AppearanceRequestAcceptedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(REQUEST_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(draft.payload()).containsEntry(NotificationPayload.VERSION, 1);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEqualTo(Map.of("2", "게스트닉"));
        assertThat(render(draft)).isEqualTo("게스트닉님이 '봄 합방' 출연 요청을 수락했습니다. 12월 1일 20:00 방송 예정입니다.");
    }

    @Test
    void 요청_거절은_호스트에게_요청서로_이동한다() {
        NotificationDraft draft = AppearanceProgressAlerts.requestRejected(
                new AppearanceRequestRejectedEvent(request(), AT), "게스트닉");

        assertThat(draft.receiverUserId()).isEqualTo(HOST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_REQUEST_REJECTED");
        assertThat(draft.sourceEventType()).isEqualTo("AppearanceRequestRejectedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(REQUEST_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE_REQUEST");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(REQUEST_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("게스트닉님이 '봄 합방' 출연 요청을 거절했습니다.");
    }

    @Test
    void 응답_전_취소는_게스트에게_호스트를_언급_회원으로_넣어_만든다() {
        NotificationDraft draft = AppearanceProgressAlerts.requestCanceled(
                new AppearanceRequestCanceledEvent(request(), AT), "호스트닉");

        assertThat(draft.receiverUserId()).isEqualTo(GUEST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_REQUEST_CANCELED");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(REQUEST_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE_REQUEST");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(REQUEST_ID));
        assertThat(NotificationPayload.nicknames(draft.payload())).isEqualTo(Map.of("1", "호스트닉"));
        assertThat(render(draft)).isEqualTo("호스트닉님이 '봄 합방' 출연 요청을 취소했습니다.");
    }

    @Test
    void 요청_만료는_호스트와_게스트에게_문구를_나눠_만든다() {
        AppearanceRequestExpiredEvent event = new AppearanceRequestExpiredEvent(request(), AT);

        NotificationDraft host = AppearanceProgressAlerts.requestExpiredForHost(event, "게스트닉");
        NotificationDraft guest = AppearanceProgressAlerts.requestExpiredForGuest(event);

        assertThat(host.receiverUserId()).isEqualTo(HOST);
        assertThat(guest.receiverUserId()).isEqualTo(GUEST);
        assertThat(host.kind()).isEqualTo("APPEARANCE_REQUEST_EXPIRED").isEqualTo(guest.kind());
        assertThat(host.sourceTargetId()).isEqualTo(String.valueOf(REQUEST_ID)).isEqualTo(guest.sourceTargetId());
        assertThat(host.targetType()).isEqualTo("APPEARANCE_REQUEST").isEqualTo(guest.targetType());
        assertThat(host.targetId()).isEqualTo(String.valueOf(REQUEST_ID)).isEqualTo(guest.targetId());
        assertThat(host.occurredAt()).isEqualTo(AT_INSTANT).isEqualTo(guest.occurredAt());
        assertThat(NotificationPayload.nicknames(host.payload())).isEqualTo(Map.of("2", "게스트닉"));
        assertThat(NotificationPayload.nicknames(guest.payload())).isEmpty();
        assertThat(render(host)).isEqualTo("게스트닉님이 응답하지 않아 '봄 합방' 출연 요청이 만료되었습니다.");
        assertThat(render(guest)).isEqualTo("응답 기한이 지나 '봄 합방' 출연 요청이 만료되었습니다.");
    }

    @Test
    void 지원_선정은_게스트에게_성립한_출연으로_이동하고_원본_대상은_지원이다() {
        NotificationDraft draft = AppearanceProgressAlerts.applicationSelected(
                new AppearanceApplicationSelectedEvent(application(), APPEARANCE_ID, SCHEDULED, AT), "호스트닉");

        assertThat(draft.receiverUserId()).isEqualTo(GUEST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_APPLICATION_SELECTED");
        assertThat(draft.sourceEventType()).isEqualTo("AppearanceApplicationSelectedEvent");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(APPLICATION_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEqualTo(Map.of("1", "호스트닉"));
        assertThat(render(draft)).isEqualTo("호스트닉님의 '가을 모집' 모집에 선정되었습니다. 12월 1일 20:00 방송 예정입니다.");
    }

    @Test
    void 미선정은_게스트에게_모집글로_이동하고_원본_대상은_지원이다() {
        NotificationDraft draft = AppearanceProgressAlerts.applicationNotSelected(
                new AppearanceApplicationNotSelectedEvent(application(), SCHEDULED, AT));

        assertThat(draft.receiverUserId()).isEqualTo(GUEST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_APPLICATION_NOT_SELECTED");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(APPLICATION_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE_RECRUIT");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(RECRUIT_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEmpty();
        assertThat(render(draft)).isEqualTo("'가을 모집' 모집에 선정되지 않았습니다.");
    }

    @Test
    void 모집_마감은_마감_일시_마감일_때만_호스트에게_만든다() {
        Optional<NotificationDraft> deadline = AppearanceProgressAlerts.recruitClosed(
                new AppearanceRecruitClosedEvent(recruit(), AppearanceRecruitCloseType.DEADLINE, AT));
        Optional<NotificationDraft> early = AppearanceProgressAlerts.recruitClosed(
                new AppearanceRecruitClosedEvent(recruit(), AppearanceRecruitCloseType.EARLY, AT));

        assertThat(early).isEmpty();
        NotificationDraft draft = deadline.orElseThrow();
        assertThat(draft.receiverUserId()).isEqualTo(HOST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_RECRUIT_CLOSED");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(RECRUIT_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE_RECRUIT");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(RECRUIT_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'가을 모집' 모집이 마감 일시가 되어 마감되었습니다.");
    }

    @Test
    void 호스트_일방_취소는_게스트에게_위약금_없이_만든다() {
        NotificationDraft draft = AppearanceProgressAlerts.canceledByHost(
                new AppearanceCanceledByHostEvent(appearance(), 30_000L, AT), "호스트닉");

        assertThat(draft.receiverUserId()).isEqualTo(GUEST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_CANCELED_BY_HOST");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(NotificationPayload.nicknames(draft.payload())).isEqualTo(Map.of("1", "호스트닉"));
        assertThat(render(draft)).isEqualTo("호스트닉님이 '봄 합방' 출연을 취소했습니다.");
    }

    @Test
    void 출연_확인은_자동_확인일_때만_호스트에게_만든다() {
        Optional<NotificationDraft> auto = AppearanceProgressAlerts.confirmed(
                new AppearanceConfirmedEvent(appearance(), AppearanceConfirmType.AUTO, AT));
        Optional<NotificationDraft> byHost = AppearanceProgressAlerts.confirmed(
                new AppearanceConfirmedEvent(appearance(), AppearanceConfirmType.HOST, AT));

        assertThat(byHost).isEmpty();
        NotificationDraft draft = auto.orElseThrow();
        assertThat(draft.receiverUserId()).isEqualTo(HOST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_CONFIRMED");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(render(draft)).isEqualTo("출연 확인 기간이 지나 '봄 합방' 출연이 자동 확인되었습니다.");
    }

    @Test
    void 불참은_게스트에게_만든다() {
        NotificationDraft draft = AppearanceProgressAlerts.absent(new AppearanceAbsentEvent(appearance(), AT));

        assertThat(draft.receiverUserId()).isEqualTo(GUEST);
        assertThat(draft.kind()).isEqualTo("APPEARANCE_ABSENT");
        assertThat(draft.sourceEventType()).isEqualTo("AppearanceAbsentEvent");
        assertThat(draft.sourceTargetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.targetType()).isEqualTo("APPEARANCE");
        assertThat(draft.targetId()).isEqualTo(String.valueOf(APPEARANCE_ID));
        assertThat(draft.occurredAt()).isEqualTo(AT_INSTANT);
        assertThat(render(draft)).isEqualTo("'봄 합방' 출연이 불참 처리되었습니다.");
    }

    @Test
    void 필수_값이_비면_거절한다() {
        assertThatThrownBy(() -> AppearanceProgressAlerts.requestAccepted(
                new AppearanceRequestAcceptedEvent(request(), null, AT), "게스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.applicationSelected(
                new AppearanceApplicationSelectedEvent(application(), null, SCHEDULED, AT), "호스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.applicationSelected(
                new AppearanceApplicationSelectedEvent(application(), APPEARANCE_ID, null, AT), "호스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.requestRejected(
                new AppearanceRequestRejectedEvent(request(), null), "게스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.requestCanceled(
                new AppearanceRequestCanceledEvent(null, AT), "호스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.requestExpiredForGuest(new AppearanceRequestExpiredEvent(
                new AppearanceRequestDto(REQUEST_ID, HOST, null, "봄 합방", 100_000L, SCHEDULED, AT), AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.applicationNotSelected(new AppearanceApplicationNotSelectedEvent(
                new AppearanceApplicationDto(APPLICATION_ID, null, "가을 모집", HOST, GUEST, AT), SCHEDULED, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.recruitClosed(
                new AppearanceRecruitClosedEvent(recruit(), null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.confirmed(
                new AppearanceConfirmedEvent(appearance(), null, AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.absent(new AppearanceAbsentEvent(
                new AppearanceDto(APPEARANCE_ID, null, HOST, GUEST, SCHEDULED), AT)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppearanceProgressAlerts.canceledByHost(
                new AppearanceCanceledByHostEvent(appearance(), 30_000L, null), "호스트닉"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AppearanceRequestDto request() {
        return new AppearanceRequestDto(REQUEST_ID, HOST, GUEST, "봄 합방", 100_000L, SCHEDULED, AT.plusDays(3));
    }

    private static AppearanceApplicationDto application() {
        return new AppearanceApplicationDto(APPLICATION_ID, RECRUIT_ID, "가을 모집", HOST, GUEST, AT.minusDays(1));
    }

    private static AppearanceRecruitDto recruit() {
        return new AppearanceRecruitDto(RECRUIT_ID, HOST, "가을 모집", AT);
    }

    private static AppearanceDto appearance() {
        return new AppearanceDto(APPEARANCE_ID, "봄 합방", HOST, GUEST, SCHEDULED);
    }

    private static String render(NotificationDraft draft) {
        return AppearanceProgressTemplates.valueOf(draft.kind()).render(draft.payload());
    }
}

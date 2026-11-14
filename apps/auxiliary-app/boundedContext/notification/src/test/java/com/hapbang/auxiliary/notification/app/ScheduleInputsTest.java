package com.hapbang.auxiliary.notification.app;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;
import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.dto.AppearanceDto;
import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCanceledByHostEvent;
import com.hapbang.shared.appearance.domain.AppearanceConfirmType;
import com.hapbang.shared.appearance.domain.AppearanceRecruitCloseType;
import com.hapbang.shared.appearance.event.AppearanceConfirmedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitClosedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitOpenedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.domain.LectureConfirmationType;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;

/**
 * 출연·강의 이벤트가 예약에 하는 일(알림 종류 7장, 이벤트 계약 0.3, ND-133·ND-138·ND-143). 생성·취소가 같은 (대상 ID, 받는 사람)을
 * 주는지가 순서 역전 처리(ND-034)의 전제다.
 */
class ScheduleInputsTest {

    private static final long HOST = 1L;
    private static final long GUEST = 2L;
    private static final LocalDateTime SCHEDULED_AT = LocalDateTime.parse("2026-11-20T20:00:00");
    private static final LocalDateTime ACCEPT_DEADLINE = LocalDateTime.parse("2026-11-18T15:00:00");
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-13T10:00:00");
    private static final AppearanceRequestDto REQUEST = new AppearanceRequestDto(10L, HOST, GUEST, "요청 제목", 1_000L,
            SCHEDULED_AT, ACCEPT_DEADLINE);
    private static final AppearanceRecruitDto RECRUIT = new AppearanceRecruitDto(20L, HOST, "모집 제목",
            LocalDateTime.parse("2026-11-19T12:00:00"));
    private static final AppearanceDto APPEARANCE = new AppearanceDto(30L, "출연 제목", HOST, GUEST, SCHEDULED_AT);

    private static Instant kst(LocalDateTime at) {
        return at.toInstant(ZoneOffset.ofHours(9));
    }

    @Test
    void 출연_요청_발송은_게스트의_만료_임박_예약을_만든다() {
        Instant recordTime = Instant.parse("2026-11-13T01:00:00Z");
        List<ScheduleCommand> commands = ScheduleInputs.requestSent(new AppearanceRequestSentEvent(REQUEST),
                recordTime);

        assertThat(commands).singleElement().isInstanceOfSatisfying(ScheduleCommand.Create.class, create -> {
            // 출연 요청 발송에는 사건 시각이 없어 레코드 timestamp로 알림 시작 시각을 본다(ND-081·ND-145)
            assertThat(create.occurredAt()).isEqualTo(recordTime);
            assertThat(create.kind()).isEqualTo(ScheduleKind.APPEARANCE_REQUEST_EXPIRING);
            assertThat(create.targetId()).isEqualTo(10L);
            assertThat(create.receiverUserId()).isEqualTo(GUEST);
            assertThat(create.times()).isEqualTo(ScheduleTimes.requestExpiring(ACCEPT_DEADLINE));
            assertThat(create.payload()).containsEntry("v", 1).containsEntry("title", "요청 제목")
                    .containsEntry("deadline", "2026-11-18T15:00");
        });
    }

    @Test
    void 요청_수락은_만료_임박을_취소하고_방송_임박_둘과_출연_확인_부탁을_만든다() {
        List<ScheduleCommand> commands = ScheduleInputs.requestAccepted(
                new AppearanceRequestAcceptedEvent(REQUEST, 30L, NOW));

        assertThat(commands).containsExactly(
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, 10L, GUEST, kst(ACCEPT_DEADLINE)),
                starting(HOST, "요청 제목"), starting(GUEST, "요청 제목"), confirm("요청 제목"));
    }

    @Test
    void 요청_거절과_응답_전_취소는_만료_임박을_취소한다() {
        ScheduleCommand cancel = new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_REQUEST_EXPIRING, 10L, GUEST,
                kst(ACCEPT_DEADLINE));

        assertThat(ScheduleInputs.requestRejected(new AppearanceRequestRejectedEvent(REQUEST, NOW)))
                .containsExactly(cancel);
        assertThat(ScheduleInputs.requestCanceled(new AppearanceRequestCanceledEvent(REQUEST, NOW)))
                .containsExactly(cancel);
    }

    @Test
    void 모집_시작은_호스트의_마감_임박을_만들고_마감과_전체_취소는_취소한다() {
        ScheduleCommand.Cancel cancel = new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_RECRUIT_CLOSING, 20L, HOST,
                kst(RECRUIT.recruitDeadline()));

        assertThat(ScheduleInputs.recruitOpened(new AppearanceRecruitOpenedEvent(RECRUIT, NOW))).singleElement()
                .isInstanceOfSatisfying(ScheduleCommand.Create.class, create -> {
                    assertThat(create.kind()).isEqualTo(ScheduleKind.APPEARANCE_RECRUIT_CLOSING);
                    assertThat(create.occurredAt()).isEqualTo(kst(NOW));
                    assertThat(create.receiverUserId()).isEqualTo(HOST);
                    assertThat(create.times()).isEqualTo(ScheduleTimes.recruitClosing(RECRUIT.recruitDeadline()));
                    assertThat(create.payload()).containsEntry("title", "모집 제목")
                            .containsEntry("deadline", "2026-11-19T12:00");
                });
        assertThat(ScheduleInputs.recruitClosed(
                new AppearanceRecruitClosedEvent(RECRUIT, AppearanceRecruitCloseType.EARLY, NOW)))
                .containsExactly(cancel);
        assertThat(ScheduleInputs.recruitCanceled(new AppearanceRecruitCanceledEvent(RECRUIT, NOW)))
                .containsExactly(cancel);
    }

    @Test
    void 지원_선정은_모집글_제목으로_방송_임박_둘과_출연_확인_부탁을_만든다() {
        AppearanceApplicationDto application = new AppearanceApplicationDto(40L, 20L, "모집 제목", HOST, GUEST, NOW);

        assertThat(ScheduleInputs.applicationSelected(
                new AppearanceApplicationSelectedEvent(application, 30L, SCHEDULED_AT, NOW)))
                .containsExactly(starting(HOST, "모집 제목"), starting(GUEST, "모집 제목"), confirm("모집 제목"));
    }

    @Test
    void 합의_취소와_호스트_일방_취소는_방송_임박_둘과_출연_확인_부탁을_취소한다() {
        List<ScheduleCommand> expected = List.of(
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_STARTING, 30L, HOST, kst(SCHEDULED_AT)),
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_STARTING, 30L, GUEST, kst(SCHEDULED_AT)),
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, 30L, HOST,
                        kst(SCHEDULED_AT.plusDays(7))));
        AppearanceCancelRequestDto cancelRequest = new AppearanceCancelRequestDto(50L, 30L, "출연 제목", HOST, GUEST, 1,
                SCHEDULED_AT);

        assertThat(ScheduleInputs.cancelRequestAccepted(new AppearanceCancelRequestAcceptedEvent(cancelRequest, NOW)))
                .isEqualTo(expected);
        assertThat(ScheduleInputs.canceledByHost(new AppearanceCanceledByHostEvent(APPEARANCE, 0L, NOW)))
                .isEqualTo(expected);
    }

    @Test
    void 출연_확인은_호스트_자동_모두_불참과_함께_출연_확인_부탁을_취소한다() {
        ScheduleCommand cancel = new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, 30L, HOST,
                kst(SCHEDULED_AT.plusDays(7)));

        assertThat(ScheduleInputs.confirmed(new AppearanceConfirmedEvent(APPEARANCE, AppearanceConfirmType.HOST, NOW)))
                .containsExactly(cancel);
        assertThat(ScheduleInputs.confirmed(new AppearanceConfirmedEvent(APPEARANCE, AppearanceConfirmType.AUTO, NOW)))
                .containsExactly(cancel);
        assertThat(ScheduleInputs.absent(new AppearanceAbsentEvent(APPEARANCE, NOW))).containsExactly(cancel);
    }

    @Test
    void 구매_완료는_항목마다_구매자의_구매_취소_마감_임박을_만든다() {
        OffsetDateTime paidAt = OffsetDateTime.parse("2026-11-13T10:30:00+09:00");
        LectureOrderPaidEvent event = new LectureOrderPaidEvent(100L, 7L, List.of(
                new LectureOrderItemDto(101L, 1L, "강의 A", 1_000L), new LectureOrderItemDto(102L, 2L, "강의 B", 0L)),
                paidAt);

        List<ScheduleCommand> commands = ScheduleInputs.orderPaid(event);

        assertThat(commands).hasSize(2).allSatisfy(command -> assertThat(command)
                .isInstanceOfSatisfying(ScheduleCommand.Create.class, create -> {
                    assertThat(create.kind()).isEqualTo(ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING);
                    assertThat(create.occurredAt()).isEqualTo(paidAt.toInstant());
                    assertThat(create.receiverUserId()).isEqualTo(7L);
                    assertThat(create.times()).isEqualTo(ScheduleTimes.orderItemConfirming(paidAt));
                    assertThat(create.payload()).containsEntry("cancelUntil", "2026-11-20");
                }));
        assertThat(commands).extracting(ScheduleCommand::targetId).containsExactly(101L, 102L);
        assertThat(((ScheduleCommand.Create) commands.get(1)).payload()).containsEntry("title", "강의 B");
    }

    // ND-143: 구매일을 몰라 대체 기한

    @Test
    void 구매_취소와_구매_확정은_대체_기한으로_구매_취소_마감_임박을_취소한다() {
        OffsetDateTime at = OffsetDateTime.parse("2026-11-15T09:00:00+09:00");

        assertThat(ScheduleInputs.orderItemCanceled(new LectureOrderItemCanceledEvent(101L, 7L, "강의 A", 1_000L, at)))
                .containsExactly(new ScheduleCommand.Cancel(ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING, 101L, 7L,
                        ScheduleTimes.orderItemFallbackDeadline(at)));
        assertThat(ScheduleInputs.orderItemConfirmed(new LectureOrderItemConfirmedEvent(101L, 7L, "강의 A",
                LectureConfirmationType.DIRECT, at)))
                .containsExactly(new ScheduleCommand.Cancel(ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING, 101L, 7L,
                        ScheduleTimes.orderItemFallbackDeadline(at)));
    }

    // ND-149: 이의 제기 중에는 구매 취소가 불가능하다

    @Test
    void 이의_제기_접수는_접수_시각_대체_기한으로_구매_취소_마감_임박을_취소한다() {
        OffsetDateTime filedAt = OffsetDateTime.parse("2026-11-16T09:00:00+09:00");

        assertThat(ScheduleInputs.objectionFiled(new LectureObjectionFiledEvent(500L, 101L, 7L, 8L, "강의 A",
                filedAt)))
                .containsExactly(new ScheduleCommand.Cancel(ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING, 101L, 7L,
                        ScheduleTimes.orderItemFallbackDeadline(filedAt)));
    }

    @Test
    void 필수_값이_비면_예외로_알린다() {
        AppearanceRequestDto noDeadline = new AppearanceRequestDto(10L, HOST, GUEST, "제목", 0L, SCHEDULED_AT, null);
        AppearanceRequestDto noTitle = new AppearanceRequestDto(10L, HOST, GUEST, null, 0L, SCHEDULED_AT,
                ACCEPT_DEADLINE);

        assertThatThrownBy(() -> ScheduleInputs.requestSent(new AppearanceRequestSentEvent(noDeadline), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleInputs.requestSent(new AppearanceRequestSentEvent(noTitle), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleInputs.requestAccepted(new AppearanceRequestAcceptedEvent(REQUEST, null, NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleInputs.orderItemCanceled(
                new LectureOrderItemCanceledEvent(101L, null, "강의", 0L, OffsetDateTime.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 사건 시각은 수락·선정 시각(NOW, KST) */
    private static ScheduleCommand starting(long receiver, String title) {
        return new ScheduleCommand.Create(ScheduleKind.APPEARANCE_STARTING, 30L, receiver,
                ScheduleTimes.starting(SCHEDULED_AT), java.util.Map.of("v", 1, "title", title, "deadline",
                "2026-11-20T20:00"), kst(NOW));
    }

    private static ScheduleCommand confirm(String title) {
        return new ScheduleCommand.Create(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, 30L, HOST,
                ScheduleTimes.confirmPeriodEnding(SCHEDULED_AT), java.util.Map.of("v", 1, "title", title, "deadline",
                "2026-11-27T20:00"), kst(NOW));
    }
}

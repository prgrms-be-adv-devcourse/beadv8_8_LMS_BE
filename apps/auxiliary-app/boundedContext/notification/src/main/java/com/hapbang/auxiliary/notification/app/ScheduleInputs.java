package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.auxiliary.notification.domain.ScheduleKind;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;
import com.hapbang.shared.appearance.dto.AppearanceDto;
import com.hapbang.shared.appearance.dto.AppearanceRecruitDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;
import com.hapbang.shared.appearance.event.AppearanceAbsentEvent;
import com.hapbang.shared.appearance.event.AppearanceApplicationSelectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCanceledByHostEvent;
import com.hapbang.shared.appearance.event.AppearanceConfirmedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitClosedEvent;
import com.hapbang.shared.appearance.event.AppearanceRecruitOpenedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.lecture.dto.LectureOrderItemDto;
import com.hapbang.shared.lecture.event.LectureObjectionFiledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemCanceledEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;

/**
 * 출연·강의 이벤트를 예약 명령으로 바꾼다(N10, 알림 종류 7장, ND-133). 생성·취소 이벤트는 같은 짝 키(대상 ID, 받는 사람)를 준다(이벤트
 * 계약 0.3, ND-138). 그래서 어느 쪽이 먼저 와도 같은 예약 행을 가리킨다(ND-034).
 * <ul>
 *     <li>출연 시각은 KST {@code LocalDateTime}이다. 출연 확인 부탁의 업무 기한은 방송 예정 일시 + 7×24시간이다.</li>
 *     <li>구매 취소·확정 이벤트는 구매일이 없어 대체 기한을 쓴다(ND-143).</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다(리스너가 재시도 없이 DLT로 보낸다).</li>
 * </ul>
 */
public final class ScheduleInputs {

    private static final ZoneId KST = ScheduleTimes.KST;
    private static final DateTimeFormatter LOCAL_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private ScheduleInputs() {
    }

    /**
     * 출연 요청 발송: 게스트에게 출연 요청 만료 임박
     *
     * @param recordTime 사건 시각이 없어 대신 쓰는 레코드 timestamp(ND-081, 알림 시작 시각 판정 ND-145)
     */
    public static List<ScheduleCommand> requestSent(AppearanceRequestSentEvent event, Instant recordTime) {
        AppearanceRequestDto request = require(event.request(), "request");
        LocalDateTime deadline = require(request.acceptDeadline(), "acceptDeadline");
        return List.of(new ScheduleCommand.Create(ScheduleKind.APPEARANCE_REQUEST_EXPIRING,
                require(request.requestId(), "requestId"), require(request.guestId(), "guestId"),
                ScheduleTimes.requestExpiring(deadline), payload(request.title(), deadline),
                require(recordTime, "recordTime")));
    }

    /** 요청 수락: 만료 임박 취소, 성립한 출연의 방송 임박(호스트·게스트)과 출연 확인 부탁(호스트) */
    public static List<ScheduleCommand> requestAccepted(AppearanceRequestAcceptedEvent event) {
        AppearanceRequestDto request = require(event.request(), "request");
        List<ScheduleCommand> commands = new ArrayList<>(requestEnded(request));
        commands.addAll(appearanceStarted(require(event.appearanceId(), "appearanceId"), request.hostId(),
                request.guestId(), request.scheduledAt(), request.title(), kst(require(event.acceptedAt(),
                        "acceptedAt"))));
        return commands;
    }

    /** 요청 거절: 만료 임박 취소 */
    public static List<ScheduleCommand> requestRejected(AppearanceRequestRejectedEvent event) {
        return requestEnded(require(event.request(), "request"));
    }

    /** 응답 전 취소: 만료 임박 취소 */
    public static List<ScheduleCommand> requestCanceled(AppearanceRequestCanceledEvent event) {
        return requestEnded(require(event.request(), "request"));
    }

    /** 모집 시작: 호스트에게 모집 마감 임박 */
    public static List<ScheduleCommand> recruitOpened(AppearanceRecruitOpenedEvent event) {
        AppearanceRecruitDto recruit = require(event.recruit(), "recruit");
        LocalDateTime deadline = require(recruit.recruitDeadline(), "recruitDeadline");
        return List.of(new ScheduleCommand.Create(ScheduleKind.APPEARANCE_RECRUIT_CLOSING,
                require(recruit.recruitId(), "recruitId"), require(recruit.hostId(), "hostId"),
                ScheduleTimes.recruitClosing(deadline), payload(recruit.title(), deadline),
                kst(require(event.openedAt(), "openedAt"))));
    }

    /** 모집 마감(조기 마감 포함): 모집 마감 임박 취소 */
    public static List<ScheduleCommand> recruitClosed(AppearanceRecruitClosedEvent event) {
        return recruitEnded(require(event.recruit(), "recruit"));
    }

    /** 모집 전체 취소: 모집 마감 임박 취소(ND-041) */
    public static List<ScheduleCommand> recruitCanceled(AppearanceRecruitCanceledEvent event) {
        return recruitEnded(require(event.recruit(), "recruit"));
    }

    /** 지원 선정: 성립한 출연의 방송 임박(호스트·게스트)과 출연 확인 부탁(호스트). 제목은 모집글 제목 */
    public static List<ScheduleCommand> applicationSelected(AppearanceApplicationSelectedEvent event) {
        var application = require(event.application(), "application");
        return appearanceStarted(require(event.appearanceId(), "appearanceId"), application.hostId(),
                application.guestId(), event.scheduledAt(), application.recruitTitle(),
                kst(require(event.selectedAt(), "selectedAt")));
    }

    /** 합의 취소: 방송 임박(호스트·게스트)과 출연 확인 부탁 취소 */
    public static List<ScheduleCommand> cancelRequestAccepted(AppearanceCancelRequestAcceptedEvent event) {
        var cancelRequest = require(event.cancelRequest(), "cancelRequest");
        return appearanceEnded(require(cancelRequest.appearanceId(), "appearanceId"), cancelRequest.hostId(),
                cancelRequest.guestId(), cancelRequest.scheduledAt());
    }

    /** 호스트 일방 취소: 방송 임박(호스트·게스트)과 출연 확인 부탁 취소 */
    public static List<ScheduleCommand> canceledByHost(AppearanceCanceledByHostEvent event) {
        AppearanceDto appearance = require(event.appearance(), "appearance");
        return appearanceEnded(require(appearance.appearanceId(), "appearanceId"), appearance.hostId(),
                appearance.guestId(), appearance.scheduledAt());
    }

    /** 출연 확인(호스트·자동 모두): 출연 확인 부탁 취소 */
    public static List<ScheduleCommand> confirmed(AppearanceConfirmedEvent event) {
        return confirmEnded(require(event.appearance(), "appearance"));
    }

    /** 불참: 출연 확인 부탁 취소 */
    public static List<ScheduleCommand> absent(AppearanceAbsentEvent event) {
        return confirmEnded(require(event.appearance(), "appearance"));
    }

    /** 구매 완료: 주문 항목마다 구매자에게 구매 취소 마감 임박. 구매일은 결제 시각의 KST 날짜 */
    public static List<ScheduleCommand> orderPaid(LectureOrderPaidEvent event) {
        Long buyer = require(event.buyerUserId(), "buyerUserId");
        OffsetDateTime paidAt = require(event.paidAt(), "paidAt");
        if (event.items() == null || event.items().isEmpty()) {
            throw new IllegalArgumentException("예약을 만들 수 없는 이벤트입니다: items");
        }
        ScheduleTimes times = ScheduleTimes.orderItemConfirming(paidAt);
        String cancelUntil = times.businessDeadline().atZone(KST).toLocalDate().minusDays(1).toString();
        List<ScheduleCommand> commands = new ArrayList<>();
        for (LectureOrderItemDto item : event.items()) {
            LectureOrderItemDto checked = require(item, "items[]");
            Map<String, Object> payload = Map.of(NotificationPayload.VERSION, ScheduleAlertTemplates.PAYLOAD_VERSION,
                    ScheduleAlertTemplates.Keys.TITLE, require(checked.lectureTitle(), "lectureTitle"),
                    ScheduleAlertTemplates.Keys.CANCEL_UNTIL, cancelUntil);
            commands.add(new ScheduleCommand.Create(ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING,
                    require(checked.orderItemId(), "orderItemId"), buyer, times, payload, paidAt.toInstant()));
        }
        return commands;
    }

    /** 구매 취소: 구매 취소 마감 임박 취소(대체 기한, ND-143) */
    public static List<ScheduleCommand> orderItemCanceled(LectureOrderItemCanceledEvent event) {
        return orderItemEnded(event.orderItemId(), event.buyerUserId(), event.canceledAt());
    }

    /** 구매 확정(직접·첫 재생·자동 모두): 구매 취소 마감 임박 취소(대체 기한, ND-143) */
    public static List<ScheduleCommand> orderItemConfirmed(LectureOrderItemConfirmedEvent event) {
        return orderItemEnded(event.orderItemId(), event.buyerUserId(), event.confirmedAt());
    }

    /**
     * 이의 제기 접수: 이의 제기 중에는 구매 취소가 불가능하므로 구매 취소 마감 임박을 취소한다(대체 기한, ND-149). 철회돼도 되살리지
     * 않는다
     */
    public static List<ScheduleCommand> objectionFiled(LectureObjectionFiledEvent event) {
        return orderItemEnded(event.orderItemId(), event.buyerUserId(), event.filedAt());
    }

    private static List<ScheduleCommand> requestEnded(AppearanceRequestDto request) {
        return List.of(new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_REQUEST_EXPIRING,
                require(request.requestId(), "requestId"), require(request.guestId(), "guestId"),
                ScheduleTimes.requestExpiring(require(request.acceptDeadline(), "acceptDeadline")).businessDeadline()));
    }

    private static List<ScheduleCommand> recruitEnded(AppearanceRecruitDto recruit) {
        return List.of(new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_RECRUIT_CLOSING,
                require(recruit.recruitId(), "recruitId"), require(recruit.hostId(), "hostId"),
                ScheduleTimes.recruitClosing(require(recruit.recruitDeadline(), "recruitDeadline"))
                        .businessDeadline()));
    }

    private static List<ScheduleCommand> appearanceStarted(long appearanceId, Long hostId, Long guestId,
            LocalDateTime scheduledAt, String title, Instant occurredAt) {
        Long host = require(hostId, "hostId");
        Long guest = require(guestId, "guestId");
        LocalDateTime at = require(scheduledAt, "scheduledAt");
        ScheduleTimes starting = ScheduleTimes.starting(at);
        ScheduleTimes confirm = ScheduleTimes.confirmPeriodEnding(at);
        return List.of(
                new ScheduleCommand.Create(ScheduleKind.APPEARANCE_STARTING, appearanceId, host, starting,
                        payload(title, at), occurredAt),
                new ScheduleCommand.Create(ScheduleKind.APPEARANCE_STARTING, appearanceId, guest, starting,
                        payload(title, at), occurredAt),
                new ScheduleCommand.Create(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, appearanceId, host, confirm,
                        payload(title, LocalDateTime.ofInstant(confirm.businessDeadline(), KST)), occurredAt));
    }

    private static List<ScheduleCommand> appearanceEnded(long appearanceId, Long hostId, Long guestId,
            LocalDateTime scheduledAt) {
        Long host = require(hostId, "hostId");
        Long guest = require(guestId, "guestId");
        LocalDateTime at = require(scheduledAt, "scheduledAt");
        Instant startingDeadline = ScheduleTimes.starting(at).businessDeadline();
        return List.of(
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_STARTING, appearanceId, host, startingDeadline),
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_STARTING, appearanceId, guest, startingDeadline),
                new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING, appearanceId, host,
                        ScheduleTimes.confirmPeriodEnding(at).businessDeadline()));
    }

    private static List<ScheduleCommand> confirmEnded(AppearanceDto appearance) {
        return List.of(new ScheduleCommand.Cancel(ScheduleKind.APPEARANCE_CONFIRM_PERIOD_ENDING,
                require(appearance.appearanceId(), "appearanceId"), require(appearance.hostId(), "hostId"),
                ScheduleTimes.confirmPeriodEnding(require(appearance.scheduledAt(), "scheduledAt"))
                        .businessDeadline()));
    }

    private static List<ScheduleCommand> orderItemEnded(Long orderItemId, Long buyerUserId, OffsetDateTime at) {
        return List.of(new ScheduleCommand.Cancel(ScheduleKind.LECTURE_ORDER_ITEM_CONFIRMING,
                require(orderItemId, "orderItemId"), require(buyerUserId, "buyerUserId"),
                ScheduleTimes.orderItemFallbackDeadline(require(at, "사건 시각"))));
    }

    private static Instant kst(LocalDateTime at) {
        return at.atZone(KST).toInstant();
    }

    private static Map<String, Object> payload(String title, LocalDateTime deadline) {
        return Map.of(NotificationPayload.VERSION, ScheduleAlertTemplates.PAYLOAD_VERSION,
                ScheduleAlertTemplates.Keys.TITLE, require(title, "title"),
                ScheduleAlertTemplates.Keys.DEADLINE, deadline.format(LOCAL_MINUTE));
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("예약을 만들 수 없는 이벤트입니다: " + name);
        }
        return value;
    }
}

package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hapbang.auxiliary.notification.app.AppearanceCancelRequestTemplates.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.appearance.dto.AppearanceCancelRequestDto;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestAcceptedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestRejectedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;

/**
 * 출연 취소 요청 3종(N6, ND-082)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 요청 도착은 호스트, 수락·거절은 게스트(알림 종류 문서 1장).</li>
 *     <li>원본 대상 ID는 취소 요청 ID다(ND-083). 같은 출연의 두 번째 취소 요청은 ID가 달라 따로 생긴다.</li>
 *     <li>이동 대상은 {@code APPEARANCE}/출연 ID다(ND-084).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다. 출연의 {@code LocalDateTime}은 KST다.</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다. 출연 3종은 #817 전에 Kafka로 나간 적이 없어 이전 형식이 없으므로
 *     소비자가 검증 실패(재시도 없이 DLT)로 바꾼다(ND-086).</li>
 * </ul>
 * 게스트 닉네임은 호출하는 쪽이 받는 사람 복제본에서 읽어 넘긴다(ND-051).
 */
public final class AppearanceCancelRequestAlerts {

    /** 출연 앱의 {@code LocalDateTime} 기준 시간대 */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String TARGET_APPEARANCE = "APPEARANCE";

    private AppearanceCancelRequestAlerts() {
    }

    /** 출연 취소 요청 도착: 호스트에게. 게스트 닉네임은 언급 회원(ND-051) */
    public static NotificationDraft requested(AppearanceCancelRequestedEvent event, String guestNickname) {
        AppearanceCancelRequestDto cancelRequest = valid(event.cancelRequest());
        Map<String, Object> payload = payload(cancelRequest);
        Map<String, Object> nicknames = new LinkedHashMap<>();
        nicknames.put(String.valueOf(cancelRequest.guestId()), guestNickname);
        payload.put(NotificationPayload.NICKNAMES, nicknames);
        return draft(cancelRequest.hostId(), AppearanceCancelRequestTemplates.APPEARANCE_CANCEL_REQUESTED, event,
                cancelRequest, payload, event.requestedAt());
    }

    /** 출연 취소 요청 수락(합의 취소): 게스트에게 */
    public static NotificationDraft accepted(AppearanceCancelRequestAcceptedEvent event) {
        AppearanceCancelRequestDto cancelRequest = valid(event.cancelRequest());
        return draft(cancelRequest.guestId(), AppearanceCancelRequestTemplates.APPEARANCE_CANCEL_REQUEST_ACCEPTED, event,
                cancelRequest, payload(cancelRequest), event.acceptedAt());
    }

    /** 출연 취소 요청 거절·자동 거절: 게스트에게. 자동 거절이면 문구가 다르다 */
    public static NotificationDraft rejected(AppearanceCancelRequestRejectedEvent event) {
        AppearanceCancelRequestDto cancelRequest = valid(event.cancelRequest());
        Map<String, Object> payload = payload(cancelRequest);
        payload.put(Keys.AUTO_REJECTED, event.autoRejected());
        return draft(cancelRequest.guestId(), AppearanceCancelRequestTemplates.APPEARANCE_CANCEL_REQUEST_REJECTED, event,
                cancelRequest, payload, event.rejectedAt());
    }

    private static AppearanceCancelRequestDto valid(AppearanceCancelRequestDto cancelRequest) {
        if (cancelRequest == null) {
            throw new IllegalArgumentException("출연 취소 요청 값이 없습니다.");
        }
        require(cancelRequest.cancelRequestId(), "cancelRequestId");
        require(cancelRequest.appearanceId(), "appearanceId");
        require(cancelRequest.title(), "title");
        require(cancelRequest.hostId(), "hostId");
        require(cancelRequest.guestId(), "guestId");
        return cancelRequest;
    }

    private static Map<String, Object> payload(AppearanceCancelRequestDto cancelRequest) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, AppearanceCancelRequestTemplates.PAYLOAD_VERSION);
        payload.put(Keys.GUEST_ID, cancelRequest.guestId());
        payload.put(Keys.TITLE, cancelRequest.title());
        return payload;
    }

    private static NotificationDraft draft(Long receiver, AppearanceCancelRequestTemplates kind, Object event,
            AppearanceCancelRequestDto cancelRequest, Map<String, Object> payload, LocalDateTime occurredAt) {
        Instant occurred = require(occurredAt, "사건 시각").atZone(KST).toInstant();
        return new NotificationDraft(receiver, kind.kind(), event.getClass().getSimpleName(),
                String.valueOf(cancelRequest.cancelRequestId()), TARGET_APPEARANCE,
                String.valueOf(cancelRequest.appearanceId()), payload, occurred);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("출연 취소 요청 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}

package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.hapbang.auxiliary.notification.app.AppearanceProgressTemplates.Keys;
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
 * 출연 진행 알림 10종(N7 출연, ND-110)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람은 알림 종류 문서 1장이다. 요청 만료는 호스트용·게스트용 초안을 따로 만든다.</li>
 *     <li>원본 대상 ID(중복 방지 키)는 이벤트 key의 사건 ID다(ND-111): 요청 4종은 요청서 ID, 지원 2종은 지원 ID, 모집 마감은 모집글 ID,
 *     출연 3종은 출연 ID.</li>
 *     <li>이동 대상은 알림 시점에 남는 업무 자원이다(ND-112). 원본 대상 ID와 따로 정한다. 요청 수락·지원 선정은 원본 대상이 요청서·지원이지만
 *     이동 대상은 이벤트에 실린 성립한 출연 ID다.</li>
 *     <li>모집 마감은 {@code DEADLINE}, 출연 확인은 {@code AUTO}일 때만 만든다. 아니면 빈 값이다(본인 행동).</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다. 출연의 {@code LocalDateTime}은 KST다.</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다. 소비자가 검증 실패(재시도 없이 DLT)로 바꾼다(ND-086과 같은 방식).
 *     M3(#871)는 처음부터 이 형식으로 나가 이전 형식이 없다.</li>
 * </ul>
 * 행동한 상대의 닉네임은 호출하는 쪽이 복제본에서 읽어 넘긴다(ND-051, ND-113). 닉네임 없이({@code null}) 바꾸면 받는 사람·발생 시각
 * 확인용 초안이 된다.
 */
public final class AppearanceProgressAlerts {

    /** 출연 앱의 {@code LocalDateTime} 기준 시간대 */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final String TARGET_APPEARANCE = "APPEARANCE";
    static final String TARGET_APPEARANCE_REQUEST = "APPEARANCE_REQUEST";
    static final String TARGET_APPEARANCE_RECRUIT = "APPEARANCE_RECRUIT";

    private AppearanceProgressAlerts() {
    }

    /** 요청 수락(출연 성립): 호스트에게. 게스트가 언급 회원. 이동 대상은 성립한 출연 */
    public static NotificationDraft requestAccepted(AppearanceRequestAcceptedEvent event, String guestNickname) {
        AppearanceRequestDto request = valid(event.request());
        Long appearanceId = require(event.appearanceId(), "appearanceId");
        Map<String, Object> payload = payload(request.title());
        payload.put(Keys.SCHEDULED_AT, require(request.scheduledAt(), "scheduledAt").toString());
        mention(payload, Keys.GUEST_ID, request.guestId(), guestNickname);
        return draft(request.hostId(), AppearanceProgressTemplates.APPEARANCE_REQUEST_ACCEPTED, event,
                request.requestId(), TARGET_APPEARANCE, appearanceId, payload, event.acceptedAt());
    }

    /** 요청 거절: 호스트에게. 게스트가 언급 회원 */
    public static NotificationDraft requestRejected(AppearanceRequestRejectedEvent event, String guestNickname) {
        AppearanceRequestDto request = valid(event.request());
        Map<String, Object> payload = payload(request.title());
        mention(payload, Keys.GUEST_ID, request.guestId(), guestNickname);
        return draft(request.hostId(), AppearanceProgressTemplates.APPEARANCE_REQUEST_REJECTED, event,
                request.requestId(), TARGET_APPEARANCE_REQUEST, request.requestId(), payload, event.rejectedAt());
    }

    /** 응답 전 취소: 게스트에게. 호스트가 언급 회원 */
    public static NotificationDraft requestCanceled(AppearanceRequestCanceledEvent event, String hostNickname) {
        AppearanceRequestDto request = valid(event.request());
        Map<String, Object> payload = payload(request.title());
        mention(payload, Keys.HOST_ID, request.hostId(), hostNickname);
        return draft(request.guestId(), AppearanceProgressTemplates.APPEARANCE_REQUEST_CANCELED, event,
                request.requestId(), TARGET_APPEARANCE_REQUEST, request.requestId(), payload, event.canceledAt());
    }

    /** 요청 만료의 호스트용: 응답하지 않은 게스트가 언급 회원 */
    public static NotificationDraft requestExpiredForHost(AppearanceRequestExpiredEvent event, String guestNickname) {
        AppearanceRequestDto request = valid(event.request());
        Map<String, Object> payload = payload(request.title());
        payload.put(Keys.RECEIVER_ROLE, Keys.RECEIVER_HOST);
        mention(payload, Keys.GUEST_ID, request.guestId(), guestNickname);
        return draft(request.hostId(), AppearanceProgressTemplates.APPEARANCE_REQUEST_EXPIRED, event,
                request.requestId(), TARGET_APPEARANCE_REQUEST, request.requestId(), payload, event.expiredAt());
    }

    /** 요청 만료의 게스트용 */
    public static NotificationDraft requestExpiredForGuest(AppearanceRequestExpiredEvent event) {
        AppearanceRequestDto request = valid(event.request());
        Map<String, Object> payload = payload(request.title());
        payload.put(Keys.RECEIVER_ROLE, Keys.RECEIVER_GUEST);
        return draft(request.guestId(), AppearanceProgressTemplates.APPEARANCE_REQUEST_EXPIRED, event,
                request.requestId(), TARGET_APPEARANCE_REQUEST, request.requestId(), payload, event.expiredAt());
    }

    /** 지원 선정(출연 성립): 게스트에게. 호스트가 언급 회원. 이동 대상은 성립한 출연 */
    public static NotificationDraft applicationSelected(AppearanceApplicationSelectedEvent event, String hostNickname) {
        AppearanceApplicationDto application = valid(event.application());
        Long appearanceId = require(event.appearanceId(), "appearanceId");
        Map<String, Object> payload = payload(application.recruitTitle());
        payload.put(Keys.SCHEDULED_AT, require(event.scheduledAt(), "scheduledAt").toString());
        mention(payload, Keys.HOST_ID, application.hostId(), hostNickname);
        return draft(application.guestId(), AppearanceProgressTemplates.APPEARANCE_APPLICATION_SELECTED, event,
                application.applicationId(), TARGET_APPEARANCE, appearanceId, payload, event.selectedAt());
    }

    /** 미선정(지원 거절, 마감 때 대기, 모집 전체 취소 때 대기): 게스트에게. 이동 대상은 모집글 */
    public static NotificationDraft applicationNotSelected(AppearanceApplicationNotSelectedEvent event) {
        AppearanceApplicationDto application = valid(event.application());
        return draft(application.guestId(), AppearanceProgressTemplates.APPEARANCE_APPLICATION_NOT_SELECTED, event,
                application.applicationId(), TARGET_APPEARANCE_RECRUIT, application.recruitId(),
                payload(application.recruitTitle()), event.notSelectedAt());
    }

    /** 모집 마감: 마감 일시 마감({@code DEADLINE})일 때만 호스트에게. 조기 마감은 본인 행동이라 빈 값 */
    public static Optional<NotificationDraft> recruitClosed(AppearanceRecruitClosedEvent event) {
        AppearanceRecruitDto recruit = valid(event.recruit());
        if (require(event.closeType(), "closeType") != AppearanceRecruitCloseType.DEADLINE) {
            return Optional.empty();
        }
        return Optional.of(draft(recruit.hostId(), AppearanceProgressTemplates.APPEARANCE_RECRUIT_CLOSED, event,
                recruit.recruitId(), TARGET_APPEARANCE_RECRUIT, recruit.recruitId(), payload(recruit.title()),
                event.closedAt()));
    }

    /** 호스트 일방 취소: 게스트에게. 호스트가 언급 회원. 위약금은 값·문구에 넣지 않는다(ND-113) */
    public static NotificationDraft canceledByHost(AppearanceCanceledByHostEvent event, String hostNickname) {
        AppearanceDto appearance = valid(event.appearance());
        Map<String, Object> payload = payload(appearance.title());
        mention(payload, Keys.HOST_ID, appearance.hostId(), hostNickname);
        return draft(appearance.guestId(), AppearanceProgressTemplates.APPEARANCE_CANCELED_BY_HOST, event,
                appearance.appearanceId(), TARGET_APPEARANCE, appearance.appearanceId(), payload, event.canceledAt());
    }

    /** 출연 확인: 자동 확인({@code AUTO})일 때만 호스트에게. 호스트 확인은 본인 행동이라 빈 값 */
    public static Optional<NotificationDraft> confirmed(AppearanceConfirmedEvent event) {
        AppearanceDto appearance = valid(event.appearance());
        if (require(event.confirmType(), "confirmType") != AppearanceConfirmType.AUTO) {
            return Optional.empty();
        }
        return Optional.of(draft(appearance.hostId(), AppearanceProgressTemplates.APPEARANCE_CONFIRMED, event,
                appearance.appearanceId(), TARGET_APPEARANCE, appearance.appearanceId(), payload(appearance.title()),
                event.confirmedAt()));
    }

    /** 불참: 게스트에게 */
    public static NotificationDraft absent(AppearanceAbsentEvent event) {
        AppearanceDto appearance = valid(event.appearance());
        return draft(appearance.guestId(), AppearanceProgressTemplates.APPEARANCE_ABSENT, event,
                appearance.appearanceId(), TARGET_APPEARANCE, appearance.appearanceId(), payload(appearance.title()),
                event.absentAt());
    }

    private static AppearanceRequestDto valid(AppearanceRequestDto request) {
        require(request, "request");
        require(request.requestId(), "requestId");
        require(request.hostId(), "hostId");
        require(request.guestId(), "guestId");
        require(request.title(), "title");
        return request;
    }

    private static AppearanceApplicationDto valid(AppearanceApplicationDto application) {
        require(application, "application");
        require(application.applicationId(), "applicationId");
        require(application.recruitId(), "recruitId");
        require(application.recruitTitle(), "recruitTitle");
        require(application.hostId(), "hostId");
        require(application.guestId(), "guestId");
        return application;
    }

    private static AppearanceRecruitDto valid(AppearanceRecruitDto recruit) {
        require(recruit, "recruit");
        require(recruit.recruitId(), "recruitId");
        require(recruit.hostId(), "hostId");
        require(recruit.title(), "title");
        return recruit;
    }

    private static AppearanceDto valid(AppearanceDto appearance) {
        require(appearance, "appearance");
        require(appearance.appearanceId(), "appearanceId");
        require(appearance.title(), "title");
        require(appearance.hostId(), "hostId");
        require(appearance.guestId(), "guestId");
        return appearance;
    }

    private static Map<String, Object> payload(String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, AppearanceProgressTemplates.PAYLOAD_VERSION);
        payload.put(Keys.TITLE, title);
        return payload;
    }

    /** 행동한 상대를 언급 회원으로 넣는다(ND-051) */
    private static void mention(Map<String, Object> payload, String memberIdKey, Long memberId, String nickname) {
        payload.put(memberIdKey, memberId);
        Map<String, Object> nicknames = new LinkedHashMap<>();
        nicknames.put(String.valueOf(memberId), nickname);
        payload.put(NotificationPayload.NICKNAMES, nicknames);
    }

    private static NotificationDraft draft(Long receiver, AppearanceProgressTemplates kind, Object event,
            Long sourceTargetId, String targetType, Long targetId, Map<String, Object> payload,
            LocalDateTime occurredAt) {
        Instant occurred = require(occurredAt, "사건 시각").atZone(KST).toInstant();
        return new NotificationDraft(receiver, kind.kind(), event.getClass().getSimpleName(),
                String.valueOf(sourceTargetId), targetType, String.valueOf(targetId), payload, occurred);
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("출연 진행 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}

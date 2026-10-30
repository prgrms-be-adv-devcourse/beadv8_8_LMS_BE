package com.hapbang.chat.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hapbang.shared.appearance.dto.AppearanceApplicationDto;
import com.hapbang.shared.appearance.dto.AppearanceRequestDto;

/**
 * 알림 메시지에 담는 값({@code chat_message.notification_data}). 서버는 문구를 만들지 않고 알림 종류별 사실만 남기며,
 * 프론트가 종류별 템플릿으로 문구를 만든다. 계약은 {@code apps/chat-app/docs/contracts/notification-data.md}다.
 * <ul>
 *     <li>모든 종류에 형식 버전 {@code v}를 담는다. 키를 빼거나 뜻·형식을 바꾸면 버전을 올린다.</li>
 *     <li>일시는 KST 기준의 오프셋 없는 {@code yyyy-MM-dd'T'HH:mm:ss} 문자열이다(API의 다른 일시 필드와 같은 형식). 금액은 숫자(HP)다.</li>
 * </ul>
 */
public final class NotificationData {

    /** 현재 형식 버전 */
    public static final int VERSION = 1;

    private static final DateTimeFormatter KST_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private NotificationData() {
    }

    /**
     * 출연 요청 도착({@link NotificationKind#APPEARANCE_REQUEST_SENT}).
     *
     * @param hostNickname 요청을 보낸 호스트의 닉네임. 알림을 남길 때의 채팅 회원 복제본 값이다
     */
    public static Map<String, Object> appearanceRequestSent(String hostNickname, AppearanceRequestDto request) {
        Map<String, Object> data = versioned();
        data.put("hostNickname", hostNickname);
        data.put("title", request.title());
        data.put("fee", request.fee());
        data.put("scheduledAt", kst(request.scheduledAt()));
        data.put("acceptDeadline", kst(request.acceptDeadline()));
        return Collections.unmodifiableMap(data);
    }

    /**
     * 출연 모집 지원 도착({@link NotificationKind#APPEARANCE_APPLICATION_SUBMITTED}).
     *
     * @param guestNickname 지원한 게스트의 닉네임. 알림을 남길 때의 채팅 회원 복제본 값이다
     */
    public static Map<String, Object> appearanceApplicationSubmitted(String guestNickname,
            AppearanceApplicationDto application) {
        Map<String, Object> data = versioned();
        data.put("guestNickname", guestNickname);
        data.put("recruitTitle", application.recruitTitle());
        return Collections.unmodifiableMap(data);
    }

    private static Map<String, Object> versioned() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("v", VERSION);
        return data;
    }

    /** 출연 모듈이 넘긴 KST 일시를 그대로 적는다 (시간대 변환 없음, 초 아래는 버린다) */
    private static String kst(LocalDateTime dateTime) {
        return KST_DATE_TIME.format(dateTime);
    }
}

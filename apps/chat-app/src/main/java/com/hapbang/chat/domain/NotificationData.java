package com.hapbang.chat.domain;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 알림 메시지에 담는 값({@code chat_message.notification_data})의 형식. 서버는 문구를 만들지 않고 알림 종류별 사실만
 * 남기며, 프론트가 종류별 템플릿으로 문구를 만든다. 계약은 {@code apps/chat-app/docs/contracts/notification-data.md}다.
 * <ul>
 *     <li>값은 알림 종류마다 record 하나로 둔다: {@link AppearanceRequestSentData}, {@link AppearanceApplicationSubmittedData},
 *     단체방 안내의 {@link GroupMembersInvitedData}, {@link GroupMemberKickedData}, {@link GroupMemberLeftData},
 *     {@link GroupRoomClosedData}. 공통 상위 타입을 두지 않고, 응답에도 종류마다 필드를 따로 둔다. 읽을 때는 알림 종류로 record를
 *     고른다.</li>
 *     <li>모든 종류에 형식 버전 {@code v}를 담는다. 키를 빼거나 뜻·형식을 바꾸면 버전을 올린다.</li>
 *     <li>일시는 KST 기준의 오프셋 없는 {@code yyyy-MM-dd'T'HH:mm:ss} 문자열이다(API의 다른 일시 필드와 같은 형식). 금액은 숫자(HP)다.
 *     일시를 문자열로 두어 직렬화 설정(Hibernate·Spring·Redis)과 관계없이 같은 형식이 된다.</li>
 *     <li>JSON으로 쓰고 읽는 것은 app의 {@code ChatStoredJson}이 한다. 도메인은 JSON 문자열만 담는다.</li>
 * </ul>
 */
public final class NotificationData {

    /** 현재 형식 버전 */
    public static final int VERSION = 1;

    private static final DateTimeFormatter KST_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private NotificationData() {
    }

    /** 출연 모듈이 넘긴 KST 일시를 그대로 적는다 (시간대 변환 없음, 초 아래는 버린다) */
    static String kst(LocalDateTime dateTime) {
        return KST_DATE_TIME.format(dateTime);
    }
}

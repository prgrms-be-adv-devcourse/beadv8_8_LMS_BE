package com.hapbang.auxiliary.notification.app;

import java.util.LinkedHashMap;
import java.util.Map;

import com.hapbang.auxiliary.notification.app.CustomerSupportAlertTemplates.Keys;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.customersupport.event.LectureObjectionFileRejectedEvent;

/**
 * N7 고객센터 2종(#918, ND-122)의 이벤트를 알림 초안으로 바꾼다.
 * <ul>
 *     <li>받는 사람: 문의 답변 도착은 문의한 회원({@code userId}), 이의 제기 접수 불가는 구매자({@code buyerUserId}).</li>
 *     <li>원본 대상 ID는 답변 ID·문의 ID다(ND-124). 같은 문의에 답변이 여러 개 달리면 답변마다 알림이 생긴다.</li>
 *     <li>이동 대상은 둘 다 {@code INQUIRY}/문의 ID다(ND-125). 원본 대상 ID와 따로 계산한다.</li>
 *     <li>발생 시각은 이벤트의 사건 시각이다(ND-021).</li>
 *     <li>필수 값이 비면 {@link IllegalArgumentException}을 던진다. 소비자는 재시도 없이 DLT로 보낸다. 문의 제목은 빈 문자열·공백뿐이어도
 *     비었다고 보고, 정상 제목은 자르지 않는다(ND-127).</li>
 * </ul>
 */
public final class CustomerSupportAlerts {

    static final String TARGET_INQUIRY = "INQUIRY";

    private CustomerSupportAlerts() {
    }

    /** 문의 답변 도착: 문의한 회원에게. 문구에 문의 제목을 쓴다 */
    public static NotificationDraft inquiryAnswered(InquiryAnsweredEvent event) {
        Map<String, Object> payload = payload();
        payload.put(Keys.TITLE, requireText(event.title(), "title"));
        return new NotificationDraft(require(event.userId(), "userId"),
                CustomerSupportAlertTemplates.INQUIRY_ANSWERED.kind(), event.getClass().getSimpleName(),
                String.valueOf(require(event.replyId(), "replyId")), TARGET_INQUIRY,
                String.valueOf(require(event.inquiryId(), "inquiryId")), payload,
                require(event.answeredAt(), "answeredAt"));
    }

    /** 이의 제기 접수 불가(재시도 배치가 끝냄): 구매자에게. 원본 대상과 이동 대상이 같은 문의 ID다 */
    public static NotificationDraft lectureObjectionFileRejected(LectureObjectionFileRejectedEvent event) {
        String inquiryId = String.valueOf(require(event.inquiryId(), "inquiryId"));
        return new NotificationDraft(require(event.buyerUserId(), "buyerUserId"),
                CustomerSupportAlertTemplates.LECTURE_OBJECTION_FILE_REJECTED.kind(), event.getClass().getSimpleName(),
                inquiryId, TARGET_INQUIRY, inquiryId, payload(), require(event.fileRejectedAt(), "fileRejectedAt"));
    }

    private static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, CustomerSupportAlertTemplates.PAYLOAD_VERSION);
        return payload;
    }

    /** 빈 문자열·공백뿐인 값도 없음으로 본다. 정상 값은 자르지 않고 받은 그대로 돌려준다(ND-127) */
    private static String requireText(String value, String name) {
        if (require(value, name).isBlank()) {
            throw new IllegalArgumentException("고객센터 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("고객센터 이벤트에 필수 값이 없습니다: " + name);
        }
        return value;
    }
}

package com.hapbang.auxiliary.notification.loadtest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestCanceledEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestExpiredEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;
import com.hapbang.shared.appearance.event.AppearanceReviewPublishedEvent;
import com.hapbang.shared.billing.event.ChargeCompletedEvent;
import com.hapbang.shared.billing.event.PayoutCompletedEvent;
import com.hapbang.shared.chat.event.GroupMembersInvitedEvent;
import com.hapbang.shared.customersupport.event.InquiryAnsweredEvent;
import com.hapbang.shared.lecture.event.LectureApprovedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureOrderItemConfirmedEvent;
import com.hapbang.shared.lecture.event.LectureOrderPaidEvent;
import com.hapbang.shared.member.event.UserJoinedEvent;

/**
 * B0 대표 이벤트와 기대 결과 표(notification-catalog 행과 ND가 근거, 구현을 보고 맞추지 않는다).
 * <ul>
 *     <li>{@code alertKinds}: 이 이벤트가 만드는 알림 종류(즉시 알림)와 예약 종류. G3이 템플릿 등록을 확인한다.</li>
 *     <li>받는 사람·알림 수·예약 효과는 {@link B0FixtureGenerator}의 기대값 계산이 이 표의 설명대로 적용한다.</li>
 * </ul>
 */
public enum B0EventType {
    /** 회원 복제본(ND-049) + USER_JOINED 가입 환영: 새 회원 1건, 원본 대상 user.id(notification-catalog, ND-073·ND-074) */
    USER_JOINED(B0Topics.MEMBER, UserJoinedEvent.class, List.of("USER_JOINED"), "복제본 1건 + 새 회원 1건"),
    /** CHARGE_COMPLETED: 충전한 회원 1건, 원본 대상 chargeId(ND-073·ND-074) */
    CHARGE(B0Topics.BILLING, ChargeCompletedEvent.class, List.of("CHARGE_COMPLETED"), "userId 1건"),
    /** PAYOUT_COMPLETED: 출연 원천 지급, 게스트 1건, 한 건씩(ND-013) */
    PAYOUT_APPEARANCE(B0Topics.BILLING, PayoutCompletedEvent.class, List.of("PAYOUT_COMPLETED"),
            "receiverUserId 1건"),
    /** PAYOUT_COMPLETED_DAILY: 강의 원천 지급, 판매자·KST 날짜마다 하나(첫 지급 CREATED, 다음 UPDATED, 같은 지급 DUPLICATE, ND-032·ND-097) */
    PAYOUT_LECTURE(B0Topics.BILLING, PayoutCompletedEvent.class, List.of("PAYOUT_COMPLETED_DAILY"),
            "판매자·날짜 묶음"),
    /** APPEARANCE_REQUEST_SENT: 게스트 1건 + 예약 APPEARANCE_REQUEST_EXPIRING(게스트, ND-133) */
    APPEARANCE_REQUEST_SENT(B0Topics.APPEARANCE, AppearanceRequestSentEvent.class,
            List.of("APPEARANCE_REQUEST_SENT", "APPEARANCE_REQUEST_EXPIRING"), "게스트 1건 + 예약 생성"),
    /** APPEARANCE_REQUEST_CANCELED: 게스트 1건 + 그 요청의 만료 임박 예약 취소(ND-133) */
    APPEARANCE_REQUEST_CANCELED(B0Topics.APPEARANCE, AppearanceRequestCanceledEvent.class,
            List.of("APPEARANCE_REQUEST_CANCELED"), "게스트 1건 + 예약 취소"),
    /** APPEARANCE_REQUEST_EXPIRED: 호스트·게스트 각 1건(ND-112) */
    APPEARANCE_REQUEST_EXPIRED(B0Topics.APPEARANCE, AppearanceRequestExpiredEvent.class,
            List.of("APPEARANCE_REQUEST_EXPIRED"), "호스트·게스트 각 1건"),
    /** APPEARANCE_APPLICATION_SUBMITTED: 호스트 1건, 원본 대상 applicationId(ND-073) */
    APPEARANCE_APPLICATION_SUBMITTED(B0Topics.APPEARANCE, AppearanceApplicationSubmittedEvent.class,
            List.of("APPEARANCE_APPLICATION_SUBMITTED"), "호스트 1건"),
    /** 알림 대상이 아닌 출연 이벤트: 알림·DLT 없이 커밋 */
    APPEARANCE_REVIEW_PUBLISHED(B0Topics.APPEARANCE, AppearanceReviewPublishedEvent.class, List.of(), "없음"),
    /** LECTURE_ORDER_PAID: 구매자 1건(주문당, ND-115) + 주문 항목마다 예약 LECTURE_ORDER_ITEM_CONFIRMING(ND-133) */
    LECTURE_ORDER_PAID(B0Topics.LECTURE, LectureOrderPaidEvent.class,
            List.of("LECTURE_ORDER_PAID", "LECTURE_ORDER_ITEM_CONFIRMING"), "구매자 1건 + 항목별 예약 생성"),
    /** LECTURE_ORDER_ITEM_CONFIRMED(AUTO): 구매자 1건 + 그 항목 예약 취소. 생성보다 먼저 와도 "취소" 행(ND-034·ND-138) */
    LECTURE_ORDER_ITEM_CONFIRMED(B0Topics.LECTURE, LectureOrderItemConfirmedEvent.class,
            List.of("LECTURE_ORDER_ITEM_CONFIRMED"), "구매자 1건 + 예약 취소"),
    /** LECTURE_APPROVED: 판매자 1건, 원본 대상 lectureId:inspectionSequence(ND-115) */
    LECTURE_APPROVED(B0Topics.LECTURE, LectureApprovedEvent.class, List.of("LECTURE_APPROVED"), "판매자 1건"),
    /** #818 이전 형식 이의 제기 결과(ND-086): 알림 없이 커밋, LEGACY 건너뜀 지표 */
    LECTURE_OBJECTION_RESOLVED_LEGACY(B0Topics.LECTURE, LectureObjectionResolvedEvent.class, List.of(), "없음"),
    /** INQUIRY_ANSWERED: 문의한 회원 1건, 원본 대상 replyId(ND-124) */
    INQUIRY_ANSWERED(B0Topics.CUSTOMERSUPPORT, InquiryAnsweredEvent.class, List.of("INQUIRY_ANSWERED"),
            "userId 1건"),
    /** GROUP_MEMBERS_INVITED: 초대된 회원마다 1건, 원본 대상 chatRoomId:inviteRequestId(ND-130) */
    GROUP_MEMBERS_INVITED(B0Topics.CHAT, GroupMembersInvitedEvent.class, List.of("GROUP_MEMBERS_INVITED"),
            "초대된 회원마다 1건");

    private final String topic;
    private final Class<?> eventClass;
    private final List<String> kinds;
    private final String effect;

    B0EventType(String topic, Class<?> eventClass, List<String> kinds, String effect) {
        this.topic = topic;
        this.eventClass = eventClass;
        this.kinds = kinds;
        this.effect = effect;
    }

    public String topic() {
        return topic;
    }

    public Class<?> eventClass() {
        return eventClass;
    }

    public String effect() {
        return effect;
    }

    /** 대표 이벤트가 만드는 알림·예약 종류 전부 */
    public static Set<String> alertKinds() {
        Set<String> kinds = new TreeSet<>();
        Arrays.stream(values()).forEach(type -> kinds.addAll(type.kinds));
        return kinds;
    }
}

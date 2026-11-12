package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.appearance.event.AppearanceApplicationSubmittedEvent;
import com.hapbang.shared.appearance.event.AppearanceCancelRequestedEvent;
import com.hapbang.shared.appearance.event.AppearanceRequestSentEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연·지갑·강의 이벤트로 만든 알림 초안을 저장한다(N5 ND-069, N6 ND-082, N7 출연 ND-110).
 * <ul>
 *     <li>업무 시각이 알림 시작 시각보다 이르면 저장하지 않는다(ND-076). 실패가 아니라 커밋한다.</li>
 *     <li>받는 사람 복제본이 없으면 저장 서비스가 실패시킨다(ND-035, 재시도 뒤 DLT). 받는 사람 정책(탈퇴·정지·관리자)도 저장 서비스가
 *     본다(ND-018·ND-072·ND-003).</li>
 *     <li>문구에 넣을 다른 회원 닉네임은 그 회원의 복제본에서 읽는다. 받는 사람이 받을 수 없는 회원이면 닉네임을 읽지 않고 건너뛰어,
 *     언급 회원 복제본이 없다는 이유로 DLT로 보내지 않는다. 받을 수 있는데 언급 회원 복제본이 없으면 실패시킨다(재시도 뒤 DLT).
 *     파기된 회원이면 저장 서비스가 파기 닉네임으로 바꾼다(ND-054).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationAlertService {

    private final NotificationWriter writer;
    private final AlertStartPolicy startPolicy;
    private final NotificationUserRepository users;
    private final ReceiverPolicy receiverPolicy;

    /** 출연 요청 도착. 사건 시각이 없어 레코드 timestamp를 대체 시각으로 쓴다(ND-081) */
    public void requestSent(AppearanceRequestSentEvent event, Instant recordTime) {
        String kind = AlertTemplate.APPEARANCE_REQUEST_SENT.kind();
        if (beforeStart(kind, recordTime) || cannotReceive(kind, event.request().guestId())) {
            return;
        }
        String host = nicknameOf(event.request().hostId());
        save(AlertDrafts.appearanceRequestSent(event, host, recordTime));
    }

    /** 모집 지원 도착. 발생 시각은 지원 시각(KST) */
    public void applicationSubmitted(AppearanceApplicationSubmittedEvent event) {
        String kind = AlertTemplate.APPEARANCE_APPLICATION_SUBMITTED.kind();
        if (beforeStart(kind, AlertDrafts.appliedAt(event.application()))
                || cannotReceive(kind, event.application().hostId())) {
            return;
        }
        String guest = nicknameOf(event.application().guestId());
        save(AlertDrafts.applicationSubmitted(event, guest));
    }

    /**
     * 출연 취소 요청 도착(N6): 호스트에게 게스트 닉네임으로 만든다(ND-085). 시작 시각·받는 사람을 먼저 본 뒤에 닉네임을 읽는다(시작
     * 시각 전이거나 받을 수 없는 회원이면 언급 회원 복제본이 없어도 DLT로 보내지 않는다)
     *
     * @param checked 호출하는 쪽이 닉네임 없이 바꿔 필수 값을 확인한 초안({@code AppearanceCancelRequestAlerts.requested(event,
     *                null)}). 받는 사람·발생 시각을 여기서 읽는다
     */
    public void cancelRequested(AppearanceCancelRequestedEvent event, NotificationDraft checked) {
        if (beforeStart(checked.kind(), checked.occurredAt())
                || cannotReceive(checked.kind(), checked.receiverUserId())) {
            return;
        }
        String guest = nicknameOf(event.cancelRequest().guestId());
        save(AppearanceCancelRequestAlerts.requested(event, guest));
    }

    /**
     * 행동한 상대의 닉네임이 들어가는 알림(N7 출연 6종, ND-113). {@link #cancelRequested}와 같은 순서로 시작 시각·받는 사람을 먼저 본 뒤에
     * 닉네임을 읽는다
     *
     * @param checked         닉네임 없이 바꿔 필수 값을 확인한 초안. 받는 사람·발생 시각을 여기서 읽는다
     * @param mentionedUserId 행동한 상대(언급 회원, ND-051)
     * @param withNickname    닉네임을 넣어 저장할 초안을 만든다
     */
    public void createMentioning(NotificationDraft checked, Long mentionedUserId,
            Function<String, NotificationDraft> withNickname) {
        if (beforeStart(checked.kind(), checked.occurredAt())
                || cannotReceive(checked.kind(), checked.receiverUserId())) {
            return;
        }
        save(withNickname.apply(nicknameOf(mentionedUserId)));
    }

    /** 다른 회원 닉네임이 없는 초안을 알림 시작 시각을 본 뒤 저장한다 */
    public void create(NotificationDraft draft) {
        if (beforeStart(draft.kind(), draft.occurredAt())) {
            return;
        }
        save(draft);
    }

    /** 강의 원천 지급을 하루 묶음에 더한다(N8). 지급 시각이 알림 시작 시각보다 이르면 더하지 않는다(ND-076) */
    public void dailyPayout(DailyPayout payout) {
        String kind = PayoutDailyTemplate.PAYOUT_COMPLETED_DAILY.kind();
        if (beforeStart(kind, payout.paidAt())) {
            return;
        }
        NotificationWriteResult result = writer.addDailyPayout(payout);
        log.info("하루 묶음에 지급을 반영했다 kind={}, receiverUserId={}, settlementId={}, outcome={}", kind,
                payout.receiverUserId(), payout.settlementId(), result.outcome());
    }

    private void save(NotificationDraft draft) {
        NotificationWriteResult result = writer.save(draft);
        log.info("알림을 만들었다 kind={}, receiverUserId={}, sourceTargetId={}, outcome={}", draft.kind(),
                draft.receiverUserId(), draft.sourceTargetId(), result.outcome());
    }

    private boolean beforeStart(String kind, Instant occurredAt) {
        if (startPolicy.allows(occurredAt)) {
            return false;
        }
        log.info("알림 시작 시각 이전 사건이라 알림을 만들지 않았다 kind={}, occurredAt={}, startAt={}", kind, occurredAt,
                startPolicy.startAt());
        return true;
    }

    /**
     * 받는 사람이 받을 수 없는 회원(탈퇴·정지·관리자)인지. 복제본이 없으면 여기서 정하지 않고 저장 서비스가 실패시키게 둔다(ND-035).
     * 저장 서비스도 같은 정책을 잠금 안에서 다시 본다
     */
    private boolean cannotReceive(String kind, Long receiverUserId) {
        boolean cannot = users.findById(receiverUserId).map(receiver -> !receiverPolicy.canReceive(receiver))
                .orElse(false);
        if (cannot) {
            log.info("받을 수 없는 회원(탈퇴·정지·관리자)이라 알림을 만들지 않았다 kind={}, receiverUserId={}", kind,
                    receiverUserId);
        }
        return cannot;
    }

    private String nicknameOf(Long memberId) {
        return users.findById(memberId).map(NotificationUser::getNickname)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.MENTIONED_NOT_REPLICATED));
    }
}

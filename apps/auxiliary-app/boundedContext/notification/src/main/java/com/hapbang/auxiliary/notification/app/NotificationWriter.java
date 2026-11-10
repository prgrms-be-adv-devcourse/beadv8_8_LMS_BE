package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.hapbang.auxiliary.notification.app.NotificationWriteResult.Outcome;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.domain.NotificationDraft;
import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 알림 저장 서비스. 이벤트를 소비하는 작업(N5 이후)이 부르는 공통 진입점이다. 한 번의 호출이 {@code notification} schema 안의
 * 로컬 트랜잭션 하나다(ND-007).
 * <ol>
 *     <li>값에 닉네임이 든 언급 회원마다 회원 단위 공유 잠금을 잡는다(ND-054). 파기 처리와 순서를 지킨다.</li>
 *     <li>받는 사람의 회원 복제본 행을 잠근다(ND-053). 같은 받는 사람의 저장은 줄을 서고, 다른 받는 사람은 기다리지 않는다.
 *     복제본이 없으면 만들지 않고 실패해 재시도 뒤 DLT로 간다(ND-035).</li>
 *     <li>받는 사람 판단({@link ReceiverPolicy}): 탈퇴 회원이면 만들지 않는다(ND-018).</li>
 *     <li>같은 사건의 알림이 이미 있으면(밀려난 알림 포함) 만들지 않는다(ND-019·ND-022). 잠금으로 같은 받는 사람의 저장이 줄을
 *     서므로 확인과 저장 사이에 끼어드는 저장이 없고, 유일 인덱스가 마지막으로 막는다.</li>
 *     <li>그 종류의 템플릿으로 문구를 만든다(ND-020·ND-050). 언급 회원 중 이미 파기된 회원의 닉네임은 {@code 탈퇴회원#{회원ID}}로
 *     바꿔 저장한다(ND-054).</li>
 *     <li>같은 트랜잭션에서 발생 시각 기준 21번째부터 Soft Delete한다(ND-022). 늦게 온 오래된 알림은 자신이 숨겨진다.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWriter {

    /** 회원마다 보이는 알림 수(ND-022) */
    public static final int KEEP = 20;

    private final NotificationRepository notifications;
    private final NotificationUserRepository users;
    private final NotificationTemplates templates;
    private final ReceiverPolicy receiverPolicy;
    private final MemberMaskLocks maskLocks;

    /**
     * @throws NotificationException 받는 사람 복제본이 없으면 {@code RECEIVER_NOT_REPLICATED}
     */
    @Transactional
    public NotificationWriteResult save(NotificationDraft draft) {
        Assert.notNull(draft, "알림 초안이 필요합니다.");
        Assert.hasText(draft.sourceEventType(), "원본 이벤트 종류가 필요합니다.");
        Assert.hasText(draft.sourceTargetId(), "원본 대상 ID가 필요합니다.");
        // 파기와의 순서(ND-054): 언급 회원의 공유 잠금을 받는 사람 행 잠금보다 먼저 잡는다
        List<Long> mentioned = NotificationPayload.nicknames(draft.payload()).keySet().stream().map(Long::valueOf)
                .toList();
        maskLocks.lockShared(mentioned);
        NotificationUser receiver = users.findForUpdate(draft.receiverUserId())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.RECEIVER_NOT_REPLICATED));
        if (!receiverPolicy.canReceive(receiver)) {
            log.info("탈퇴한 회원이라 알림을 만들지 않음 receiverUserId={}, kind={}", receiver.getId(), draft.kind());
            return NotificationWriteResult.of(Outcome.SKIPPED_WITHDRAWN);
        }
        if (notifications.existsBySourceEventTypeAndSourceTargetIdAndReceiverUserId(draft.sourceEventType(),
                draft.sourceTargetId(), draft.receiverUserId())) {
            log.debug("같은 사건의 알림이 이미 있어 만들지 않음 receiverUserId={}, sourceEventType={}, sourceTargetId={}",
                    draft.receiverUserId(), draft.sourceEventType(), draft.sourceTargetId());
            return NotificationWriteResult.of(Outcome.DUPLICATE);
        }
        Function<Map<String, Object>, String> renderer = templates.renderer(draft.kind());
        Notification notification = Notification.create(draft, renderer);
        // 이미 파기된 언급 회원은 옛 닉네임 대신 파기 닉네임으로 저장한다(ND-054)
        if (!mentioned.isEmpty()) {
            users.findMaskedIds(mentioned).forEach(maskedId -> notification.maskNickname(maskedId, renderer));
        }
        Notification saved = notifications.save(notification);
        keepLatest(draft.receiverUserId(), Instant.now());
        return NotificationWriteResult.created(saved.getId());
    }

    private void keepLatest(Long receiverUserId, Instant now) {
        List<Notification> alive = notifications
                .findByReceiverUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(receiverUserId);
        for (int i = KEEP; i < alive.size(); i++) {
            alive.get(i).softDelete(now);
        }
    }
}

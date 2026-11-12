package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *     <li>commit 뒤에 실시간 전달을 맡긴다({@link NotificationRealtimeRelay}, ND-027). 중복·탈퇴로 만들지 않은 알림은 넘기지 않는다.</li>
 * </ol>
 * 잠금 순서는 회원 advisory lock → 받는 사람 복제본 행 → 알림 행(ID 오름차순)이다. 파기·읽음도 같은 순서를 따르므로 교착 상태가 없다
 * (module.md 3.2 "동시성").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWriter {

    /** 회원마다 보이는 알림 수(ND-022) */
    public static final int KEEP = 20;

    /** 하루 묶음 행의 원본 이벤트 종류. 원본 대상 ID는 비운다(ND-103) */
    static final String BUNDLE_SOURCE_EVENT_TYPE = "PayoutCompletedEvent";

    private final NotificationRepository notifications;
    private final NotificationUserRepository users;
    private final NotificationTemplates templates;
    private final ReceiverPolicy receiverPolicy;
    private final MemberMaskLocks maskLocks;
    private final NotificationRealtimeRelay realtime;
    private final PayoutItemStore payoutItems;

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
            log.info("받을 수 없는 회원(탈퇴·정지·관리자)이라 알림을 만들지 않음 receiverUserId={}, kind={}", receiver.getId(),
                    draft.kind());
            return NotificationWriteResult.of(Outcome.SKIPPED_RECEIVER);
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
        // commit 뒤에만, 저장 스레드 밖에서 실시간으로 넘긴다. 밀려난 알림이면 넘기지 않는다(ND-027·ND-059)
        realtime.publishAfterCommit(saved.getId(), draft.receiverUserId());
        return NotificationWriteResult.created(saved.getId());
    }

    /**
     * 강의 원천 지급을 판매자·날짜의 하루 묶음에 더한다(ND-032·ND-097·ND-101·ND-103). 아래가 모두 한 트랜잭션이고, 어느 하나가
     * 실패하면(예: 지급 기록) 묶음 갱신까지 전부 되돌아간다.
     * <ol>
     *     <li>받는 사람 복제본 행을 잠근다(ND-053). 같은 판매자의 지급·알림 저장은 모두 이 잠금으로 줄을 선다. 복제본이 없으면 실패해
     *     재시도 뒤 DLT로 간다(ND-035). 받을 수 없는 회원이면 아무것도 쓰지 않는다(ND-018·ND-072).</li>
     *     <li>이미 더한 지급이면 묶음을 되살리거나 읽음·발생 시각을 바꾸지 않고 중복으로 끝낸다.</li>
     *     <li>그 날짜 묶음(삭제된 행 포함)이 없으면 만들고, 있으면 행을 잠근 뒤 금액·건수를 더하고 발생 시각을 늦은 값으로, 다시 안
     *     읽음으로 바꾸며 밀려났으면 되살린다.</li>
     *     <li>지급을 기록하고, 21번째부터 숨긴다(ND-022). 되살린 묶음이 21번째보다 오래되면 다시 숨는다.</li>
     *     <li>commit 뒤 실시간으로 넘긴다(ND-059). 숨겨진 묶음이면 넘기지 않는다.</li>
     * </ol>
     *
     * @throws NotificationException 받는 사람 복제본이 없으면 {@code RECEIVER_NOT_REPLICATED}
     */
    @Transactional
    public NotificationWriteResult addDailyPayout(DailyPayout payout) {
        Assert.notNull(payout, "지급이 필요합니다.");
        Assert.notNull(payout.settlementId(), "지급 건 ID가 필요합니다.");
        Assert.notNull(payout.paidAt(), "지급 시각이 필요합니다.");
        String kind = PayoutDailyTemplate.PAYOUT_COMPLETED_DAILY.kind();
        NotificationUser receiver = users.findForUpdate(payout.receiverUserId())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.RECEIVER_NOT_REPLICATED));
        if (!receiverPolicy.canReceive(receiver)) {
            log.info("받을 수 없는 회원(탈퇴·정지·관리자)이라 하루 묶음에 더하지 않았다 receiverUserId={}, kind={}",
                    receiver.getId(), kind);
            return NotificationWriteResult.of(Outcome.SKIPPED_RECEIVER);
        }
        if (payoutItems.exists(payout.settlementId())) {
            log.debug("이미 하루 묶음에 더한 지급이라 건너뛰었다 receiverUserId={}, settlementId={}",
                    payout.receiverUserId(), payout.settlementId());
            return NotificationWriteResult.of(Outcome.DUPLICATE);
        }
        Function<Map<String, Object>, String> renderer = templates.renderer(kind);
        Optional<Long> existing = notifications.findBundleId(kind, payout.receiverUserId(), payout.bundleKey());
        long bundleId;
        Outcome outcome;
        if (existing.isEmpty()) {
            Map<String, Object> payload = PayoutDailyTemplate.payload(payout.bundleDate(), payout.paidAmountHp(), 1);
            NotificationDraft draft = new NotificationDraft(payout.receiverUserId(), kind, BUNDLE_SOURCE_EVENT_TYPE,
                    null, null, null, payload, payout.paidAt());
            bundleId = notifications.save(Notification.createBundle(draft, payout.bundleKey(), renderer)).getId();
            outcome = Outcome.CREATED;
        } else {
            bundleId = existing.get();
            addTo(bundleId, payout, renderer);
            outcome = Outcome.UPDATED;
        }
        payoutItems.record(payout, bundleId);
        keepLatest(payout.receiverUserId(), Instant.now());
        realtime.publishAfterCommit(bundleId, payout.receiverUserId());
        return new NotificationWriteResult(outcome, bundleId);
    }

    /** 묶음 행을 잠그고 잠근 뒤의 값에 지급을 더한다 */
    private void addTo(long bundleId, DailyPayout payout, Function<Map<String, Object>, String> renderer) {
        notifications.lockInIdOrder(List.of(bundleId));
        Notification current = notifications.findById(bundleId).orElseThrow();
        Map<String, Object> payload = PayoutDailyTemplate.added(current.getPayload(), payout);
        Instant occurredAt = current.getOccurredAt().isAfter(payout.paidAt()) ? current.getOccurredAt()
                : payout.paidAt();
        notifications.addToBundle(bundleId, payload, renderer.apply(payload), occurredAt, LocalDateTime.now());
    }

    /**
     * 21번째부터 숨긴다. 엔티티를 불러와 고치지 않는다: 밀어낼 행을 ID 오름차순으로 잠근 뒤 삭제 시각만 채우는 벌크 UPDATE다.
     * 그래서 같은 행의 읽은 시각·값·문구를 바꾸는 다른 처리(읽음, 파기)의 변경을 덮어쓰지 않는다(#801 PR 리뷰).
     */
    private void keepLatest(Long receiverUserId, Instant now) {
        List<Long> alive = notifications.findAliveIdsNewestFirst(receiverUserId);
        if (alive.size() <= KEEP) {
            return;
        }
        List<Long> pushedOut = alive.subList(KEEP, alive.size());
        notifications.lockInIdOrder(pushedOut);
        notifications.softDeleteAll(pushedOut, now);
    }
}

package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.domain.ScheduleTimes;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 예약 알림의 예약 생성·취소(N10, ND-029·ND-034·ND-133·ND-136).
 * <ul>
 *     <li>생성: 사건이 알림 시작 시각 전이면 만들지 않는다(ND-145). 받는 사람 복제본이 없으면 실패한다(ND-035·ND-146, 재시도 뒤
 *     DLT). 받을 수 없는 회원(탈퇴·정지·관리자)이면 만들지 않는다
 *     (ND-018·ND-072). 업무 기한이 지났으면 "건너뜀" 행만 남긴다(ND-136·ND-144). 보낼 시각만 지났으면 예약으로 만들어 다음 배치가
 *     보낸다(ND-135).</li>
 *     <li>취소: 받는 사람을 보지 않는다. 예약이 없으면 "취소" 행을 남긴다(ND-034).</li>
 *     <li>리스너 한 레코드의 예약 처리와 즉시 알림은 {@link #applyWith}로 한 트랜잭션이다(ND-133). 예약 행을 먼저 쓰고 알림(받는 사람
 *     행 잠금)을 나중에 써서, 예약 행 → 받는 사람 행 순서로 잠그는 예약 배치와 교착하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleStore store;
    private final NotificationUserRepository users;
    private final ReceiverPolicy receiverPolicy;
    private final AlertStartPolicy startPolicy;

    /**
     * 예약 처리를 먼저 하고 같은 트랜잭션에서 즉시 알림을 만든다. 어느 하나가 실패하면 모두 되돌린다
     *
     * @param alerts 같은 레코드의 즉시 알림 저장
     */
    @Transactional
    public void applyWith(List<? extends ScheduleCommand> commands, Runnable alerts) {
        Instant now = Instant.now();
        for (ScheduleCommand command : commands) {
            switch (command) {
                case ScheduleCommand.Create create -> create(create, now);
                case ScheduleCommand.Cancel cancel -> cancel(cancel, now);
            }
        }
        alerts.run();
    }

    /**
     * @throws NotificationException 받는 사람 복제본이 없으면 {@code RECEIVER_NOT_REPLICATED}
     */
    @Transactional
    public void create(ScheduleCommand.Create command, Instant now) {
        // 시작 시각 전 사건은 받는 사람 복제본을 보지 않고 넘긴다(복제본이 없어도 DLT로 보내지 않는다, ND-145)
        if (!startPolicy.allows(command.occurredAt())) {
            log.info("알림 시작 시각 이전 사건이라 예약을 만들지 않았다 kind={}, targetId={}, occurredAt={}, startAt={}",
                    command.kind(), command.targetId(), command.occurredAt(), startPolicy.startAt());
            return;
        }
        NotificationUser receiver = users.findById(command.receiverUserId())
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.RECEIVER_NOT_REPLICATED));
        if (!receiverPolicy.canReceive(receiver)) {
            log.info("받을 수 없는 회원(탈퇴·정지·관리자)이라 예약을 만들지 않았다 kind={}, targetId={}, receiverUserId={}",
                    command.kind(), command.targetId(), command.receiverUserId());
            return;
        }
        boolean past = ScheduleTimes.isPast(command.times().businessDeadline(), now);
        ScheduleStore.Status status = past ? ScheduleStore.Status.SKIPPED : ScheduleStore.Status.SCHEDULED;
        boolean inserted = store.insertIfAbsent(command, status, now);
        log.info("예약 생성을 처리했다 kind={}, targetId={}, receiverUserId={}, status={}, inserted={}", command.kind(),
                command.targetId(), command.receiverUserId(), status, inserted);
    }

    @Transactional
    public void cancel(ScheduleCommand.Cancel command, Instant now) {
        boolean changed = store.cancel(command, now);
        log.info("예약 취소를 처리했다 kind={}, targetId={}, receiverUserId={}, changed={}", command.kind(),
                command.targetId(), command.receiverUserId(), changed);
    }
}

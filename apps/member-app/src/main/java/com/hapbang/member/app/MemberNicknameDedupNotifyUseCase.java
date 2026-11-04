package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.NicknameDedup;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.NicknameDedupRepository;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 마이그레이션이 바꾼 중복 닉네임을 다른 앱 복제본에 알린다 (#646). SQL로만 바꾸면 UserModifiedEvent가 나가지 않아
 * 다시 맞추기(backfill)가 없는 앱의 닉네임이 옛것으로 남기 때문이다.
 * <ul>
 *     <li>회원의 지금 상태를 보낸다(상태를 담은 이벤트). 그 사이 회원이 닉네임을 다시 바꿨어도 최신 값이 간다.</li>
 *     <li>이벤트(아웃박스)와 알림 표시를 같은 트랜잭션에 남겨 다시 시작해도 한 번만 보낸다. Kafka가 잠시 안 되면
 *     재발행 배치(#421)가 보낸다.</li>
 * </ul>
 * 트랜잭션은 Facade가 연다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberNicknameDedupNotifyUseCase {

    private final NicknameDedupRepository nicknameDedupRepository;
    private final MemberSupport memberSupport;
    private final ApplicationEventPublisher eventPublisher;

    /** 알린 회원 수 */
    public int notifyPending() {
        List<NicknameDedup> pending = nicknameDedupRepository.findPendingForUpdate();
        if (pending.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        for (NicknameDedup dedup : pending) {
            User user = memberSupport.findUser(dedup.getUserId());
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
            dedup.markNotified(now);
        }
        log.info("중복 정리로 바뀐 닉네임 {}건을 다른 앱에 알렸습니다.", pending.size());
        return pending.size();
    }
}

package com.hapbang.auxiliary.notification.app;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.MemberMaskLocks;
import com.hapbang.auxiliary.notification.out.NotificationRepository;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 파기된 회원의 닉네임을 저장된 알림에서 바꾼다(ND-036·ND-051·ND-054). 한 번의 호출이 한 트랜잭션이다.
 * <ol>
 *     <li>그 회원에 회원 단위 배타 잠금을 잡는다. 그 회원을 언급한 저장이 끝날 때까지 기다린다(ND-054).</li>
 *     <li>회원 복제본에 파기 표시를 남긴다. 복제본이 없으면 파기 상태로 만든다. 이 뒤의 저장은 파기 닉네임으로 저장된다.</li>
 *     <li>언급 회원 ID 칸(GIN)으로 그 회원이 든 알림만 찾아(밀려난 알림 포함) 값 속 닉네임을 {@code 탈퇴회원#{회원ID}}로 바꾸고
 *     문구를 그 종류의 템플릿으로 다시 만든다.</li>
 * </ol>
 * 같은 파기를 다시 받아도 결과가 같다. 파기된 복제본은 뒤에 오는 가입·변경 이벤트로 바뀌지 않으므로 순서와 상관없다.
 */
@Service
@RequiredArgsConstructor
public class NotificationMaskingService {

    private final NotificationRepository notifications;
    private final NotificationUserRepository users;
    private final NotificationTemplates templates;
    private final MemberMaskLocks maskLocks;

    /** @return 이번에 바뀐 알림 수 */
    @Transactional
    public int mask(long userId) {
        maskLocks.lockExclusive(userId);
        Instant now = Instant.now();
        users.findForUpdate(userId).ifPresentOrElse(user -> user.markMasked(now),
                () -> users.save(NotificationUser.createMasked(userId, now)));
        int changed = 0;
        for (Notification notification : notifications.findMentioning(userId)) {
            if (notification.maskNickname(userId, templates.renderer(notification.getKind()))) {
                changed++;
            }
        }
        return changed;
    }
}

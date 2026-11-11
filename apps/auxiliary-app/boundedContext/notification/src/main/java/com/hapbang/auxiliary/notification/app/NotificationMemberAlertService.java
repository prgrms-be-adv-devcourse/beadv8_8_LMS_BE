package com.hapbang.auxiliary.notification.app;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.notification.app.NotificationUserSyncResult.Outcome;
import com.hapbang.auxiliary.notification.domain.NotificationUser;
import com.hapbang.auxiliary.notification.out.NotificationUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트를 복제본에 반영하고, 같은 트랜잭션에서 가입 환영·승급 승인 알림을 만든다(ND-080). 회원 복제본 리스너가 부르며, 출연·지갑
 * 알림 소비자와 달리 Backfill을 기다리지 않는다: 받는 사람이 그 이벤트로 반영되는 회원이라 복제본이 없을 일이 없다.
 * <ul>
 *     <li>발생 시각은 레코드 timestamp(대체 시각, ND-081)다. 알림 시작 시각보다 이르면 복제본만 반영한다(ND-076).</li>
 *     <li>승급 승인은 반영 <b>전</b> 복제본 역할이 인플루언서이고, 이 이벤트가 실제로 반영되었으며(더 큰 버전), 새 역할이 인증된
 *     인플루언서일 때만 만든다(ND-016·ND-071). 복제본이 없을 때 온 변경은 이전 역할을 몰라 만들지 않는다.</li>
 *     <li>유저 → 인플루언서(채널 인증 승인) 역할 변경으로는 만들지 않는다(ND-042).</li>
 *     <li>관리자·탈퇴·정지 회원은 저장 서비스의 받는 사람 정책으로 걸러진다. 알림 저장이 실패하면 복제본 반영도 롤백되고 재시도 뒤 DLT로
 *     간다.</li>
 * </ul>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class NotificationMemberAlertService {

    private final NotificationUserSyncService syncService;
    private final NotificationAlertService alerts;
    private final NotificationUserRepository users;

    public NotificationUserSyncResult joined(UserDto user, Instant recordTime) {
        NotificationUserSyncResult result = syncService.syncJoined(user);
        alerts.create(AlertDrafts.userJoined(user, recordTime));
        return result;
    }

    public NotificationUserSyncResult modified(UserDto user, Instant recordTime) {
        Role before = user.id() == null ? null
                : users.findById(user.id()).map(NotificationUser::getRole).orElse(null);
        NotificationUserSyncResult result = syncService.syncModified(user);
        if (result.outcome() == Outcome.UPDATED && before == Role.INFLUENCER
                && user.role() == Role.VERIFIED_INFLUENCER) {
            alerts.create(AlertDrafts.upgradeApproved(user, recordTime));
        }
        return result;
    }
}

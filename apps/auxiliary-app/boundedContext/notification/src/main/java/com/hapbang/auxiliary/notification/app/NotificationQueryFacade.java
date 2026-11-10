package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.notification.app.dto.NotificationItem;
import com.hapbang.auxiliary.notification.app.dto.ReadAllResult;
import com.hapbang.auxiliary.notification.app.dto.UnreadCount;
import com.hapbang.auxiliary.notification.app.exception.NotificationErrorCode;
import com.hapbang.auxiliary.notification.app.exception.NotificationException;
import com.hapbang.auxiliary.notification.domain.Notification;
import com.hapbang.auxiliary.notification.out.NotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * 알림 조회·읽음(ND-021~023·026·052). 모든 메서드는 로그인 회원 본인의 알림만 다룬다. 비로그인은 공통 SecurityConfig가 401로,
 * 관리자는 {@code @PreAuthorize}가 403으로 막는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryFacade {

    private final NotificationRepository notifications;

    /** 삭제되지 않은 최신 20개, 발생 시각 내림차순(ND-021·ND-022). 목록을 연 것만으로는 읽지 않는다(ND-023) */
    @PreAuthorize(NotificationRoles.RECEIVER)
    public List<NotificationItem> list(Long userId) {
        return notifications.findTop20ByReceiverUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(userId).stream()
                .map(NotificationItem::from).toList();
    }

    @PreAuthorize(NotificationRoles.RECEIVER)
    public UnreadCount unreadCount(Long userId) {
        return new UnreadCount(notifications.countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(userId));
    }

    /**
     * 누른 알림을 읽음으로 바꾼다. 이미 읽었으면 처음 시각 그대로 성공한다(ND-052).
     *
     * @throws NotificationException 남의·없는·밀려난 알림이면 모두 {@code NOTIFICATION_NOT_FOUND}
     */
    @Transactional
    @PreAuthorize(NotificationRoles.RECEIVER)
    public NotificationItem markRead(Long userId, Long notificationId) {
        Notification notification = notifications.findByIdAndReceiverUserIdAndDeletedAtIsNull(notificationId, userId)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        notification.markRead(Instant.now());
        return NotificationItem.from(notification);
    }

    /** 보이는 알림 중 안 읽은 것을 모두 읽음으로 바꾼다. 다시 해도 결과가 같다 */
    @Transactional
    @PreAuthorize(NotificationRoles.RECEIVER)
    public ReadAllResult markAllRead(Long userId) {
        return new ReadAllResult(notifications.markAllRead(userId, Instant.now()));
    }
}

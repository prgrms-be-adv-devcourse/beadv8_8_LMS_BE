package com.hapbang.auxiliary.notification.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.notification.app.NotificationQueryFacade;
import com.hapbang.auxiliary.notification.app.dto.NotificationItem;
import com.hapbang.auxiliary.notification.app.dto.ReadAllResult;
import com.hapbang.auxiliary.notification.app.dto.UnreadCount;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 알림센터 REST(`docs/api/notification.md`, ND-026·ND-052). 로그인 회원 본인의 알림만 다룬다. 유저·인플루언서·인증된 인플루언서만
 * 쓰고 관리자는 403이다. 연결 티켓·WebSocket은 실시간 전달(N3)에서 더한다.
 */
@Tag(name = "Notification", description = "알림센터 (유저·인플루언서·인증된 인플루언서)")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class ApiV1NotificationController {

    private final NotificationQueryFacade facade;

    @Operation(summary = "알림 목록", description = "삭제되지 않은 최신 20개를 발생 시각 내림차순으로 준다. cursor·페이지는 없다. "
            + "목록을 연 것만으로는 읽음이 되지 않는다.")
    @GetMapping
    public RsData<List<NotificationItem>> list(@LoginUserId Long userId) {
        return RsData.ok("알림 목록입니다.", facade.list(userId));
    }

    @Operation(summary = "안 읽은 알림 수", description = "보이는 최신 20개 안에서 센다(최대 20).")
    @GetMapping("/unread-count")
    public RsData<UnreadCount> unreadCount(@LoginUserId Long userId) {
        return RsData.ok("안 읽은 알림 수입니다.", facade.unreadCount(userId));
    }

    @Operation(summary = "읽음", description = "알림을 눌렀을 때 부른다. 이미 읽었으면 처음 읽은 시각 그대로 200이다. "
            + "남의 알림·없는 알림·밀려난 알림은 모두 404 NOTIFICATION_NOT_FOUND다.")
    @PatchMapping("/{notificationId}/read")
    public RsData<NotificationItem> markRead(@LoginUserId Long userId, @PathVariable Long notificationId) {
        return RsData.ok("알림을 읽음으로 바꿨습니다.", facade.markRead(userId, notificationId));
    }

    @Operation(summary = "전체 읽음", description = "보이는 알림 중 안 읽은 것을 모두 읽음으로 바꾼다. 다시 해도 결과가 같다.")
    @PatchMapping("/read-all")
    public RsData<ReadAllResult> markAllRead(@LoginUserId Long userId) {
        return RsData.ok("알림을 모두 읽음으로 바꿨습니다.", facade.markAllRead(userId));
    }
}

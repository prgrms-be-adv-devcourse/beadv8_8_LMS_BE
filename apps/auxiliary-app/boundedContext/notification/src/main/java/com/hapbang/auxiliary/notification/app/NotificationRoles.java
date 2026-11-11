package com.hapbang.auxiliary.notification.app;

/** 알림 API의 역할 검사({@code @PreAuthorize}) */
final class NotificationRoles {

    /** 알림을 받는 역할: 유저·인플루언서·인증된 인플루언서. 관리자는 알림센터를 쓰지 않는다(ND-003·ND-052) */
    static final String RECEIVER = "hasAnyRole('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER')";

    private NotificationRoles() {
    }
}

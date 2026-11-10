package com.hapbang.auxiliary.notification.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.auxiliary.notification.domain.NotificationUser;

/** 알림 회원 복제본({@code notification.notification_user}) */
public interface NotificationUserRepository extends JpaRepository<NotificationUser, Long> {
}

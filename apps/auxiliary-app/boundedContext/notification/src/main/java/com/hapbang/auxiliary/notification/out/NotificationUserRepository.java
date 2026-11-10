package com.hapbang.auxiliary.notification.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.auxiliary.notification.domain.NotificationUser;

import jakarta.persistence.LockModeType;

/** 알림 회원 복제본({@code notification.notification_user}) */
public interface NotificationUserRepository extends JpaRepository<NotificationUser, Long> {

    /** 받는 사람 행을 잠근다(SELECT … FOR UPDATE). 같은 받는 사람의 알림 저장을 줄 세운다(ND-053) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from NotificationUser u where u.id = :id")
    Optional<NotificationUser> findForUpdate(@Param("id") Long id);

    /** 이 중 파기된 회원 ID(ND-054) */
    @Query("select u.id from NotificationUser u where u.id in :ids and u.maskedAt is not null")
    List<Long> findMaskedIds(@Param("ids") Collection<Long> ids);
}

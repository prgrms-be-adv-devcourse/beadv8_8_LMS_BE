package com.hapbang.auxiliary.notification.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Backfill 반영(ND-077): 회원 한 명을 한 문장으로 반영한다. 없으면 만들고, 있으면 파기되지 않았고 버전 규칙
     * ({@code ReplicaUser.syncFrom}: 반영한 버전이 없거나, 들어온 버전이 더 크다)을 만족할 때만 바꾼다. 행 잠금 안에서 비교하므로 같은
     * 회원의 Kafka 변경과 겹쳐도 더 큰 버전이 남는다. 바꾸면 낙관적 락 버전을 올려, 먼저 읽어 둔 JPA 갱신이 덮지 못하게 한다.
     * 만든·고친 시각은 JPA Auditing과 같은 JVM 시각({@code now})을 넘긴다(DB 세션 시간대에 기대지 않는다).
     * <p>
     * 회원마다 자기 트랜잭션으로 부른다. 실행 뒤 영속성 컨텍스트를 비우므로 바깥 트랜잭션 안에서 부르지 않는다(먼저 읽은 엔티티가
     * 분리된다).
     *
     * @return 반영한 행 수(만들었거나 바꿨으면 1, 그대로 두면 0)
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into notification.notification_user
                (id, nickname, role, status, member_version, version, created_at, updated_at)
            values (:id, :nickname, :role, :status, :memberVersion, 0, :now, :now)
            on conflict (id) do update
                set nickname = excluded.nickname,
                    role = excluded.role,
                    status = excluded.status,
                    member_version = excluded.member_version,
                    version = notification.notification_user.version + 1,
                    updated_at = excluded.updated_at
                where notification.notification_user.masked_at is null
                  and (notification.notification_user.member_version is null
                       or (excluded.member_version is not null
                           and excluded.member_version > notification.notification_user.member_version))
            """, nativeQuery = true)
    int upsertFromBackfill(@Param("id") Long id, @Param("nickname") String nickname, @Param("role") String role,
            @Param("status") String status, @Param("memberVersion") Long memberVersion,
            @Param("now") LocalDateTime now);
}

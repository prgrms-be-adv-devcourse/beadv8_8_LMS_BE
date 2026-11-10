package com.hapbang.auxiliary.notification.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.auxiliary.notification.domain.Notification;

/** 알림({@code notification.notification}). 행을 지우는 메서드를 두지 않는다(ND-022) */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 같은 사건의 알림이 이미 있는지. 삭제된(밀려난) 행도 포함한다(ND-019·ND-022) */
    boolean existsBySourceEventTypeAndSourceTargetIdAndReceiverUserId(String sourceEventType, String sourceTargetId,
            Long receiverUserId);

    /** 목록: 삭제되지 않은 최신 20개, 발생 시각 내림차순(ND-021·ND-022) */
    List<Notification> findTop20ByReceiverUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(Long receiverUserId);

    /** 보존 정리용: 삭제되지 않은 알림 전부, 발생 시각 내림차순 */
    List<Notification> findByReceiverUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(Long receiverUserId);

    long countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(Long receiverUserId);

    /** 본인의 삭제되지 않은 알림 한 건. 남의·없는·밀려난 알림은 모두 비어 있다(ND-052) */
    Optional<Notification> findByIdAndReceiverUserIdAndDeletedAtIsNull(Long id, Long receiverUserId);

    @Modifying
    @Query("""
            update Notification n set n.readAt = :now
            where n.receiverUserId = :receiverUserId and n.deletedAt is null and n.readAt is null""")
    int markAllRead(@Param("receiverUserId") Long receiverUserId, @Param("now") Instant now);

    /** 그 회원이 언급된 알림(삭제된 행 포함). GIN 인덱스를 쓴다(ND-051). ID 순서라 파기 처리끼리 행 잠금 순서가 같다 */
    @Query(value = """
            select * from notification.notification
            where mentioned_user_ids @> array[cast(:userId as bigint)]
            order by id""", nativeQuery = true)
    List<Notification> findMentioning(@Param("userId") long userId);
}

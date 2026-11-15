package com.hapbang.auxiliary.notification.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.auxiliary.notification.domain.Notification;

/**
 * 알림({@code notification.notification}). 행을 지우는 메서드를 두지 않는다(ND-022).
 * <p>
 * 동시성(#801 PR 리뷰): 같은 행을 여러 처리가 쓰므로 엔티티의 행 전체 덮어쓰기에 기대지 않는다. 읽음·전체 읽음·보존 정리는 자기 칸만
 * 바꾸는 조건부 벌크 UPDATE이고, 여러 행을 잠글 때는 언제나 ID 오름차순이다. 파기만 엔티티를 쓰며 행을 먼저 잠근다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 같은 사건의 알림이 이미 있는지. 삭제된(밀려난) 행도 포함한다(ND-019·ND-022) */
    boolean existsBySourceEventTypeAndSourceTargetIdAndReceiverUserId(String sourceEventType, String sourceTargetId,
            Long receiverUserId);

    /** 목록: 삭제되지 않은 최신 20개, 발생 시각 내림차순(ND-021·ND-022) */
    List<Notification> findTop20ByReceiverUserIdAndDeletedAtIsNullOrderByOccurredAtDescIdDesc(Long receiverUserId);

    /** 보존 정리용: 삭제되지 않은 알림 ID, 발생 시각 내림차순(엔티티를 불러오지 않는다) */
    @Query("""
            select n.id from Notification n
            where n.receiverUserId = :receiverUserId and n.deletedAt is null
            order by n.occurredAt desc, n.id desc""")
    List<Long> findAliveIdsNewestFirst(@Param("receiverUserId") Long receiverUserId);

    long countByReceiverUserIdAndDeletedAtIsNullAndReadAtIsNull(Long receiverUserId);

    /** 본인의 삭제되지 않은 알림 한 건. 남의·없는·밀려난 알림은 모두 비어 있다(ND-052) */
    Optional<Notification> findByIdAndReceiverUserIdAndDeletedAtIsNull(Long id, Long receiverUserId);

    /** 알림 행을 ID 오름차순으로 잠근다(SELECT … FOR UPDATE). 잠근 행의 ID를 돌려준다 */
    @Query(value = "select id from notification.notification where id in (:ids) order by id for update",
            nativeQuery = true)
    List<Long> lockInIdOrder(@Param("ids") Collection<Long> ids);

    /** 받는 사람의 보이는 안 읽은 알림을 ID 오름차순으로 잠근다(전체 읽음) */
    @Query(value = """
            select id from notification.notification
            where receiver_user_id = :receiverUserId and deleted_at is null and read_at is null
            order by id for update""", nativeQuery = true)
    List<Long> lockUnreadInIdOrder(@Param("receiverUserId") Long receiverUserId);

    /** 보존 정리: 삭제 시각만 채운다(ND-022). 다른 칸(읽은 시각·값·문구)은 건드리지 않는다 */
    @Modifying
    @Query("update Notification n set n.deletedAt = :now where n.id in :ids and n.deletedAt is null")
    int softDeleteAll(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /**
     * 읽음: 본인의 보이고 안 읽은 알림이면 읽은 시각만 채운다(ND-023·ND-052).
     *
     * @return 실제로 바뀐 행 수(0 또는 1). 0이면 남의·없는·밀려난·이미 읽은 알림이다
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Notification n set n.readAt = :now
            where n.id = :id and n.receiverUserId = :receiverUserId and n.deletedAt is null and n.readAt is null""")
    int markReadIfUnread(@Param("id") Long id, @Param("receiverUserId") Long receiverUserId, @Param("now") Instant now);

    /** 전체 읽음: 잠근 행 중 아직 보이고 안 읽은 것만 읽은 시각을 채운다. 실제로 바뀐 행 수 */
    @Modifying(clearAutomatically = true)
    @Query("""
            update Notification n set n.readAt = :now
            where n.id in :ids and n.deletedAt is null and n.readAt is null""")
    int markReadAll(@Param("ids") Collection<Long> ids, @Param("now") Instant now);

    /**
     * 그 회원이 언급된 알림(삭제된 행 포함)을 ID 오름차순으로 잠가 가져온다. GIN 인덱스를 쓴다(ND-051). 잠금을 얻은 뒤의 최신 값이다.
     */
    @Query(value = """
            select * from notification.notification
            where mentioned_user_ids @> array[cast(:userId as bigint)]
            order by id for update""", nativeQuery = true)
    List<Notification> lockMentioning(@Param("userId") long userId);
}

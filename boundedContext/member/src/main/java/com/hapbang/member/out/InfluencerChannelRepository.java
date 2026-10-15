package com.hapbang.member.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.shared.member.domain.UserStatus;

import jakarta.persistence.LockModeType;

public interface InfluencerChannelRepository extends JpaRepository<InfluencerChannel, Long> {

    boolean existsByPlatformAndChannelIdentifier(ChannelPlatform platform, String channelIdentifier);

    /** 이 회원의 채널일 때만 (삭제된 채널 제외) */
    Optional<InfluencerChannel> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    List<InfluencerChannel> findByUserIdAndDeletedAtIsNullOrderByIdAsc(Long userId);

    /** 심사 목록: 상태별, 삭제된 채널·해당 상태가 아닌 회원 제외, 회원(닉네임)을 함께 읽는다 */
    @EntityGraph(attributePaths = "user")
    Page<InfluencerChannel> findByVerificationStatusAndDeletedAtIsNullAndUserStatus(
            ChannelVerificationStatus status, UserStatus userStatus, Pageable pageable);

    /** 승인·반려 처리용 조회: 행을 잠가(FOR UPDATE) 관리자 동시 처리를 막는다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from InfluencerChannel c where c.id = :id and c.deletedAt is null")
    Optional<InfluencerChannel> findByIdForUpdate(@Param("id") Long id);

    /** 여러 회원의 채널을 상태별로 한 번에 (승급 심사 목록에 승인된 채널 붙이기, N+1 방지) */
    List<InfluencerChannel> findByUserIdInAndVerificationStatusAndDeletedAtIsNullOrderByIdAsc(
            Collection<Long> userIds, ChannelVerificationStatus status);
}

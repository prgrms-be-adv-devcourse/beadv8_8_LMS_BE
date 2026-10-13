package com.hapbang.member.out;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;

public interface InfluencerChannelRepository extends JpaRepository<InfluencerChannel, Long> {

    boolean existsByPlatformAndChannelIdentifier(ChannelPlatform platform, String channelIdentifier);

    /** 이 회원의 채널일 때만 (삭제된 채널 제외) */
    Optional<InfluencerChannel> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    List<InfluencerChannel> findByUserIdAndDeletedAtIsNullOrderByIdAsc(Long userId);
}

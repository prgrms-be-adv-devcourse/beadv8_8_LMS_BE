package com.hapbang.member.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.ChannelPlatform;
import com.hapbang.member.domain.InfluencerChannel;

public interface InfluencerChannelRepository extends JpaRepository<InfluencerChannel, Long> {

    boolean existsByPlatformAndChannelIdentifier(ChannelPlatform platform, String channelIdentifier);
}

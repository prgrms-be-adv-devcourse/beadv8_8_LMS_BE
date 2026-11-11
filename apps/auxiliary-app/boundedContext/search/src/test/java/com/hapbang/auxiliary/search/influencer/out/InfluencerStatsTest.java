package com.hapbang.auxiliary.search.influencer.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.shared.member.dto.InfluencerChannelDto;
import com.hapbang.shared.member.dto.InfluencerStatsDto;
import com.hapbang.shared.member.dto.RatingDto;

/** 회원 통계 → 문서 통계(#771) */
class InfluencerStatsTest {

    @Test
    @DisplayName("팔로워는 합하지 않고 가장 큰 채널 값이다. 같으면 먼저 온 채널(채널 ID가 작은 쪽)이 대표다")
    void representativeChannel() {
        InfluencerStats stats = InfluencerStats.from(new InfluencerStatsDto(1L, 3L, List.of(
                new InfluencerChannelDto("클립", 100), new InfluencerChannelDto("본 채널", 900),
                new InfluencerChannelDto("다시보기", 900)), new RatingDto(4.2, 7)));

        assertThat(stats).isEqualTo(new InfluencerStats(1L, List.of("클립", "본 채널", "다시보기"), "본 채널", 900, 4.2, 7));
    }

    @Test
    @DisplayName("채널이 없으면 대표 채널 없음·팔로워 0, 리뷰가 없으면 평균 없음이다")
    void empty() {
        InfluencerStats stats = InfluencerStats.from(new InfluencerStatsDto(1L, 3L, List.of(), new RatingDto(null, 0)));

        assertThat(stats).isEqualTo(new InfluencerStats(1L, List.of(), null, 0, null, 0));
    }
}

package com.hapbang.auxiliary.search.influencer.out;

import java.util.Comparator;
import java.util.List;

import com.hapbang.shared.member.dto.InfluencerChannelDto;
import com.hapbang.shared.member.dto.InfluencerStatsDto;

/**
 * 문서에 쓰는 인플루언서 통계(#771). 회원 앱 통계(#769)에서 만든다.
 *
 * @param userId                    회원 ID
 * @param channelNames              인증된 채널 이름 모두(검색 대상)
 * @param representativeChannelName 팔로워가 가장 많은 채널 이름(응답에 보여 줌). 채널이 없으면 null
 * @param followerCount             가장 큰 채널의 팔로워 수(필터·정렬·가산). 채널이 없으면 0. 합하지 않는다: 부 채널 팔로워는 대부분 같은 사람이다
 * @param ratingAverage             받은 출연 리뷰 전체 평균. 리뷰가 없으면 null
 * @param ratingCount               받은 출연 리뷰 수
 */
public record InfluencerStats(long userId, List<String> channelNames, String representativeChannelName,
                              long followerCount, Double ratingAverage, long ratingCount) {

    public InfluencerStats {
        channelNames = List.copyOf(channelNames);
    }

    /** 회원 앱 통계에서 만든다. 팔로워가 같은 채널이 여럿이면 먼저 온 채널(채널 ID가 작은 쪽)이 대표다 */
    public static InfluencerStats from(InfluencerStatsDto dto) {
        List<InfluencerChannelDto> channels = dto.channels();
        InfluencerChannelDto representative = channels.stream()
                // max는 같은 값이면 먼저 온 것을 남긴다
                .max(Comparator.comparingInt(InfluencerChannelDto::followerCount))
                .orElse(null);
        return new InfluencerStats(dto.userId(), channels.stream().map(InfluencerChannelDto::channelName).toList(),
                representative == null ? null : representative.channelName(),
                representative == null ? 0 : representative.followerCount(),
                dto.appearanceRating().average(), dto.appearanceRating().count());
    }
}

package com.hapbang.shared.member.dto;

import java.util.List;
import java.util.Objects;

/**
 * 인플루언서 한 명의 통계 (#769). 검색이 채널 이름·팔로워·출연 평점을 색인하고, version으로 프로필이 최신인지 확인한다.
 *
 * @param userId           회원 ID
 * @param version          회원 원본 버전(UserDto.version과 같은 값)
 * @param channels         인증된(승인된, 삭제되지 않은) 채널 모두. 채널 ID 오름차순, 없으면 빈 목록
 * @param appearanceRating 받은 출연 리뷰 전체의 평점
 */
public record InfluencerStatsDto(Long userId, Long version, List<InfluencerChannelDto> channels,
                                 RatingDto appearanceRating) {

    // 빠진 응답은 Client가 읽을 수 없는 응답으로 거절한다
    public InfluencerStatsDto {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        channels = List.copyOf(Objects.requireNonNull(channels, "channels는 필수입니다."));
        Objects.requireNonNull(appearanceRating, "appearanceRating은 필수입니다.");
    }
}

package com.hapbang.member.app;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.RatingSummary;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.dto.InfluencerChannelDto;
import com.hapbang.shared.member.dto.InfluencerStatsDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.RatingDto;

import lombok.RequiredArgsConstructor;

/** 다른 모듈이 내부 HTTP API로 부르는 회원 조회 (#303). 내부 API 키로 인증한 호출만 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class MemberInternalFacade {

    private final UserRepository userRepository;
    private final MemberSupport memberSupport;
    private final InfluencerChannelRepository influencerChannelRepository;
    private final MemberAppearanceRatingUseCase memberAppearanceRatingUseCase;

    /** 회원 ID 오름차순 키셋 목록. afterId가 없으면 처음부터, size+1개를 읽어 다음 페이지가 있는지 판단한다 */
    @Transactional(readOnly = true)
    public MemberPageDto findMembers(Long afterId, int size) {
        List<User> users = userRepository.findByIdGreaterThanOrderByIdAsc(
                afterId == null ? 0L : afterId, Limit.of(size + 1));
        boolean hasNext = users.size() > size;
        List<User> page = hasNext ? users.subList(0, size) : users;
        return new MemberPageDto(page.stream().map(User::toDto).toList(),
                hasNext ? page.getLast().getId() : null);
    }

    /**
     * 정상 인플루언서 통계 (#769). 회원 ID 오름차순 키셋이고, size+1명을 읽어 다음 페이지가 있는지 판단한다.
     * 한 페이지 회원의 채널·평점을 한 번씩 묶어 읽는다(회원 수와 상관없이 SQL 3번)
     */
    @Transactional(readOnly = true)
    public InfluencerStatsPageDto findInfluencerStats(Long afterId, int size) {
        List<User> users = memberSupport.findInfluencersAfter(afterId == null ? 0L : afterId, size + 1);
        boolean hasNext = users.size() > size;
        List<User> page = hasNext ? users.subList(0, size) : users;
        if (page.isEmpty()) {
            return new InfluencerStatsPageDto(List.of(), null);
        }
        List<Long> userIds = page.stream().map(User::getId).toList();
        Map<Long, List<InfluencerChannelDto>> channels = approvedChannelsOf(userIds);
        Map<Long, AppearanceRatingSummary> ratings = memberAppearanceRatingUseCase.findAll(userIds);
        List<InfluencerStatsDto> items = page.stream()
                .map(user -> new InfluencerStatsDto(user.getId(), user.getVersion(),
                        channels.getOrDefault(user.getId(), List.of()),
                        toRatingDto(ratings.getOrDefault(user.getId(), AppearanceRatingSummary.EMPTY).total())))
                .toList();
        return new InfluencerStatsPageDto(items, hasNext ? page.getLast().getId() : null);
    }

    /** 회원별 인증된 채널(승인, 삭제 안 됨). 채널 ID 오름차순을 지킨다 */
    private Map<Long, List<InfluencerChannelDto>> approvedChannelsOf(List<Long> userIds) {
        return influencerChannelRepository.findByUserIdInAndVerificationStatusAndDeletedAtIsNullOrderByIdAsc(
                        userIds, ChannelVerificationStatus.APPROVED).stream()
                .collect(Collectors.groupingBy(channel -> channel.getUser().getId(),
                        Collectors.mapping(MemberInternalFacade::toChannelDto, Collectors.toList())));
    }

    private static InfluencerChannelDto toChannelDto(InfluencerChannel channel) {
        return new InfluencerChannelDto(channel.getChannelName(), channel.getFollowerCount());
    }

    private static RatingDto toRatingDto(RatingSummary summary) {
        return new RatingDto(summary.average(), summary.count());
    }
}

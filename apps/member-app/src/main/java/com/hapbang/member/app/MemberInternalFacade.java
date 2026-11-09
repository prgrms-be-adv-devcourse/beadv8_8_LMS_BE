package com.hapbang.member.app;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.AppearanceRatingSummary;
import com.hapbang.member.domain.ChannelVerificationStatus;
import com.hapbang.member.domain.ContentCategory;
import com.hapbang.member.domain.InfluencerChannel;
import com.hapbang.member.domain.RatingSummary;
import com.hapbang.member.domain.User;
import com.hapbang.member.out.ContentCategoryRepository;
import com.hapbang.member.out.InfluencerChannelRepository;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.ContentCategoryDto;
import com.hapbang.shared.member.dto.InfluencerChannelDto;
import com.hapbang.shared.member.dto.InfluencerStatsDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.MemberPageDto;
import com.hapbang.shared.member.dto.RatingDto;
import com.hapbang.shared.member.dto.UserDto;

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
    private final ContentCategoryRepository contentCategoryRepository;

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
     * 회원 변경분 (#769). users.updated_at이 커서보다 뒤인 회원을 (updatedAt, id) 오름차순 키셋으로 준다. 모든 역할·상태를 포함해,
     * 인플루언서가 아니게 된 회원(강등·정지·탈퇴)도 받는 쪽이 숨길 수 있다. 커서가 없으면 처음부터 읽는다.
     * 항목이 있으면 마지막 페이지에도 다음 커서를 주고, 더 있는지는 hasNext로 알린다.
     * 분야는 @BatchSize로 100명씩 묶어 읽는다
     *
     * @param afterUpdatedAt 커서 시각. afterId와 함께 넘기거나 함께 null이다(컨트롤러가 확인)
     */
    @Transactional(readOnly = true)
    public MemberChangePageDto findChanges(LocalDateTime afterUpdatedAt, Long afterId, int size) {
        Limit limit = Limit.of(size + 1);
        List<User> users = afterUpdatedAt == null
                ? userRepository.findByUpdatedAtIsNotNullOrderByUpdatedAtAscIdAsc(limit)
                : userRepository.findChangedAfter(afterUpdatedAt, afterId, limit);
        boolean hasNext = users.size() > size;
        List<User> page = hasNext ? users.subList(0, size) : users;
        List<UserDto> items = page.stream().map(User::toDto).toList();
        if (page.isEmpty()) {
            return new MemberChangePageDto(items, null, null, false);
        }
        // 마지막 페이지에도 커서를 준다. 받는 쪽은 UserDto에 updatedAt이 없어 커서를 스스로 만들 수 없다
        User last = page.getLast();
        return new MemberChangePageDto(items, last.getUpdatedAt(), last.getId(), hasNext);
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

    /**
     * 콘텐츠 분야 전체 목록 (#769). 이미 고른 회원의 문서에 이름을 붙일 수 있게 active가 꺼진 분야도 준다.
     * 순서는 코드 오름차순이다(공개 목록 API의 "고른 수 많은 순"은 화면용)
     */
    @Transactional(readOnly = true)
    public ContentCategoriesDto findContentCategories() {
        return new ContentCategoriesDto(contentCategoryRepository.findAllByOrderByCodeAsc().stream()
                .map(MemberInternalFacade::toContentCategoryDto)
                .toList());
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

    private static ContentCategoryDto toContentCategoryDto(ContentCategory category) {
        return new ContentCategoryDto(category.getCode(), category.getName(), category.isActive());
    }

    private static RatingDto toRatingDto(RatingSummary summary) {
        return new RatingDto(summary.average(), summary.count());
    }
}

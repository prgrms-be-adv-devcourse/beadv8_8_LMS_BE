package com.hapbang.auxiliary.search.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.hapbang.auxiliary.search.influencer.out.MemberStatsSource;
import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.ContentCategoryDto;
import com.hapbang.shared.member.dto.InfluencerChannelDto;
import com.hapbang.shared.member.dto.InfluencerStatsDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.RatingDto;

/**
 * 회원 앱 대신 통계·분야 목록을 주는 가짜(#771). 통계는 회원 ID 오름차순으로 넣고, 페이지 크기대로 잘라 키셋처럼 준다.
 * {@link #failCategories()}로 분야 목록 장애를 흉내 낸다.
 */
public class FakeMemberStatsSource implements MemberStatsSource {

    private final List<InfluencerStatsDto> stats = new ArrayList<>();
    private List<ContentCategoryDto> categories = List.of();
    private RuntimeException categoriesFailure;
    private final AtomicInteger categoryCalls = new AtomicInteger();
    private int pageSize = Integer.MAX_VALUE;

    public FakeMemberStatsSource categories(ContentCategoryDto... categories) {
        this.categories = List.of(categories);
        this.categoriesFailure = null;
        return this;
    }

    public FakeMemberStatsSource failCategories() {
        this.categoriesFailure = new IllegalStateException("회원 앱 장애");
        return this;
    }

    /** 인플루언서 통계 한 명. 채널은 이름·팔로워 쌍이다 */
    public FakeMemberStatsSource influencer(long userId, Double ratingAverage, long ratingCount,
            InfluencerChannelDto... channels) {
        stats.add(new InfluencerStatsDto(userId, 1L, List.of(channels), new RatingDto(ratingAverage, ratingCount)));
        return this;
    }

    /** 페이지 크기를 실제 상한보다 작게 해 여러 페이지를 흉내 낸다 */
    public FakeMemberStatsSource pageSize(int pageSize) {
        this.pageSize = pageSize;
        return this;
    }

    /** 넣은 통계를 비운다 */
    public FakeMemberStatsSource reset() {
        stats.clear();
        return this;
    }

    public int categoryCalls() {
        return categoryCalls.get();
    }

    @Override
    public InfluencerStatsPageDto influencerStats(Long afterId, int size) {
        List<InfluencerStatsDto> page = stats.stream()
                .filter(s -> afterId == null || s.userId() > afterId)
                .limit(Math.min(size, pageSize))
                .toList();
        boolean last = page.isEmpty() || page.getLast().userId().equals(stats.getLast().userId());
        return new InfluencerStatsPageDto(page, last ? null : page.getLast().userId());
    }

    @Override
    public ContentCategoriesDto contentCategories() {
        categoryCalls.incrementAndGet();
        if (categoriesFailure != null) {
            throw categoriesFailure;
        }
        return new ContentCategoriesDto(categories);
    }

    public static InfluencerChannelDto channel(String name, int followers) {
        return new InfluencerChannelDto(name, followers);
    }

    public static ContentCategoryDto category(String code, String name) {
        return new ContentCategoryDto(code, name, true);
    }
}

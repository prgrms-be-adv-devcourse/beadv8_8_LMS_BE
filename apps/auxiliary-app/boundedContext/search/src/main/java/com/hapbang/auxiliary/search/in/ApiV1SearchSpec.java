package com.hapbang.auxiliary.search.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.search.app.dto.InfluencerSearchItem;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Tag(name = "Search", description = "검색 (로그인 회원, #767). 강의 검색 등 다른 대상은 대상별 경로로 더한다")
public interface ApiV1SearchSpec {

    @Operation(summary = "인플루언서 검색", description = """
            닉네임·채널 이름·콘텐츠 분야 이름·자기소개로 인플루언서를 찾는다. 줄임말(롤·배그·메이플 등)은 동의어로 함께 찾는다. \
            기본 순서는 검색어가 있으면 관련도순(텍스트가 먼저, 비슷하면 팔로워·인증이 가름), 없으면 팔로워 많은 순이다. \
            분야만 여러 개 고르면 많이 맞는 순 → 팔로워 순이다. \
            sort는 followers,desc(팔로워 많은 순)·rating,desc(출연 평점 높은 순, 리뷰가 적으면 전체 평균 쪽으로 보정) 하나만 쓸 수 있고 \
            그 밖은 400 UNSUPPORTED_SORT. 앞에서 1,000건까지만 볼 수 있고(totalElements도 1,000까지) 넘으면 400 PAGE_TOO_DEEP. \
            검색할 수 없으면 503 SEARCH_UNAVAILABLE(회원 앱의 인플루언서 목록은 그대로 쓸 수 있다). \
            팔로워·채널·평점은 대개 10분 안에 회원 앱 값을 따라간다. 프로필 사진은 없다(회원 사진 일괄 API로 채운다).""")
    RsData<Page<InfluencerSearchItem>> searchInfluencers(
            @Parameter(description = "검색어(최대 100자). 없으면 조건만으로 찾는다", example = "메이플") @Size(max = 100) String keyword,
            @Parameter(description = "콘텐츠 분야 코드(최대 10개). 하나라도 가진 사람(OR), 많이 맞을수록 위",
                    example = "GAME,MAPLESTORY") @Size(max = 10) List<String> contentCategories,
            @Parameter(description = "true면 인증된 인플루언서만, false면 인증 안 된 인플루언서만. 없으면 모두") Boolean verified,
            @Parameter(description = "팔로워 최소값(0 이상, 팔로워가 가장 많은 채널 기준)", example = "1000") @PositiveOrZero Long minFollowers,
            @Parameter(description = "출연 평점 최소값(0~5, 원래 평균). 리뷰가 없는 사람은 빠진다", example = "4.0") @DecimalMin("0") @DecimalMax("5") Double minRating,
            @Parameter(description = "page(0부터), size(기본 20, 최대 100), sort(followers,desc 또는 rating,desc)") Pageable pageable);
}

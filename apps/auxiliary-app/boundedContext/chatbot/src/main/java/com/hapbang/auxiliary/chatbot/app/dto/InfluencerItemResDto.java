package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.auxiliary.chatbot.domain.InfluencerReason;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 추천 인플루언서 한 명 (#719). 닉네임·프로필 사진은 담지 않는다. FE가 {@code memberId}로 회원 프로필 API를 불러 카드를 그린다.
 *
 * @param explanation 추천 이유 문장(LLM 설명 경로만, 위젯 API는 없음)
 */
@Schema(description = "추천 인플루언서")
public record InfluencerItemResDto(
        @Schema(description = "회원 ID. 카드는 GET /api/v1/members/{memberId}로 그린다", example = "42")
        long memberId,
        @Schema(description = "콘텐츠 분야 한글 이름(회원이 고른 순서)", example = "[\"게임\", \"음악\"]")
        List<String> categoryNames,
        @Schema(description = "함께 마친 출연 수(호스트·게스트 모두)", example = "3")
        long completedAppearances,
        @Schema(description = "받은 출연 리뷰 평균 별점(소수 첫째 자리). 리뷰가 없으면 null", example = "4.5", nullable = true)
        @Nullable Double averageRating,
        @Schema(description = "추천 이유 코드. SIMILAR_CATEGORY(내 분야·소개와 비슷), SIMILAR_TO_PARTNERS(함께 출연했던 인플루언서들과 비슷), "
                + "MATCHES_INTEREST(말한 관심사와 맞음), POPULAR(출연·리뷰가 좋은 인기 인플루언서), PAST_PARTNER(예전에 함께 출연함)")
        List<InfluencerReason> reasons,
        @Schema(description = "추천 이유 문장(LLM 설명 경로만). LLM이 실패하면 이유 코드의 정해진 문장", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Nullable String explanation
) {
}

package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import com.hapbang.auxiliary.chatbot.app.LectureRecommendation;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;

import io.swagger.v3.oas.annotations.media.Schema;

/** 강의 추천 응답 (#718) */
public record LectureRecommendResDto(
        @Schema(description = "추천 강의(최대 5개, 순서대로 보여 준다). 요청 회원이 탈퇴 상태면 빈 목록") List<Item> items,
        @Schema(description = "true면 활동 기록과 관심사가 없어 인기 강의만 줬다. 관심사를 입력받아 interest로 다시 부른다")
        boolean needsInterest,
        @Schema(description = "false면 모두 맞춤이 아닌 인기 강의다(제목을 '지금 인기 있는 강의'로). 활동이 없거나, 잠시 색인을 쓸 수 없을 때")
        boolean personalized) {

    public static LectureRecommendResDto from(LectureRecommendation recommendation) {
        List<Item> items = recommendation.items().stream()
                .map(item -> new Item(item.lectureId(), item.title(), item.score(), item.reason(), item.reasonText()))
                .toList();
        return new LectureRecommendResDto(items, recommendation.needsInterest(), recommendation.personalized());
    }

    /** 추천 강의 하나 */
    public record Item(
            @Schema(description = "강의 ID") long lectureId,
            @Schema(description = "강의 제목") String title,
            @Schema(description = "최종 점수((1−β)·유사도 + β·인기). 인기순으로 채운 강의는 0. 순서는 items 순서를 따른다") double score,
            @Schema(description = "SIMILAR_TO_PURCHASED(구매한 강의와 비슷함), SIMILAR_TO_CART(장바구니 강의와 비슷함), "
                    + "MATCHES_INTEREST(관심사와 맞음), POPULAR(인기순으로 채움)") LectureRecommendReason reason,
            @Schema(description = "카드에 보여 줄 이유 문장") String reasonText) {
    }
}

package com.hapbang.auxiliary.chatbot.app.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hapbang.auxiliary.chatbot.app.LectureRecommendation;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 강의 추천 응답 (#718, #720). 인플루언서 추천 응답과 같은 이름으로 근거 코드·{@code fallback}·{@code notice}를 둔다.
 *
 * @param fallback LLM 이유 경로에서 LLM 답을 쓰지 못하고 이유 코드 문장으로 답했는지. 위젯 API는 늘 false
 * @param notice   추천하지 못한 까닭을 사용자에게 보여 줄 정해진 문구. 추천했으면 없다
 */
@Schema(description = "강의 추천 결과")
public record LectureRecommendResDto(
        @Schema(description = "추천 강의(서버 설정 수까지, 순서대로 보여 준다). 요청 회원이 숨김이면 빈 목록이고 notice가 온다")
        List<Item> items,
        @Schema(description = "true면 지금은 맞춤으로 고를 수 없어 인기 강의를 줬다(활동 기록이 없거나 활동한 강의가 아직 색인 전). "
                + "관심사를 입력받아 interest로 다시 부르면 맞춤으로 찾는다")
        boolean needsInterest,
        @Schema(description = "후보의 근거. PERSONALIZED(맞춤 후보가 있음, 모자란 자리는 인기순으로 채움), "
                + "POPULAR(맞춤으로 고를 재료가 없어 인기순, 다시 시도해도 같음), "
                + "POPULAR_TEMPORARY(검색이 잠시 실패해 인기순으로 대신 답함. 이때만 '잠시 뒤 다시 시도' 안내와 버튼을 보여 준다)")
        RecommendBasis basis,
        @Schema(description = "LLM 이유 경로에서 LLM 답을 쓰지 못해 정해진 문장으로 답했다. 위젯 API는 늘 false")
        boolean fallback,
        @Schema(description = "추천하지 못했을 때 화면에 띄울 정해진 문구(예: 가입 직후 회원 정보를 아직 받지 못함). 추천했으면 빠진다",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Nullable String notice) {

    /** 요청 회원이 숨김(가입 직후 동기화 전·탈퇴·마스킹)일 때 보여 줄 문구 */
    public static final String REQUESTER_HIDDEN_NOTICE = "지금은 강의를 추천할 수 없어요. 가입한 지 얼마 안 됐다면 잠시 뒤 다시 시도해 주세요.";

    /** 위젯 응답(LLM 없음) */
    public static LectureRecommendResDto from(LectureRecommendation recommendation) {
        return from(recommendation, false);
    }

    public static LectureRecommendResDto from(LectureRecommendation recommendation, boolean fallback) {
        List<Item> items = recommendation.items().stream()
                .map(item -> new Item(item.lectureId(), item.title(), item.score(), item.reason(), item.reasonText()))
                .toList();
        return new LectureRecommendResDto(items, recommendation.needsInterest(), recommendation.basis(), fallback,
                recommendation.requesterHidden() ? REQUESTER_HIDDEN_NOTICE : null);
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

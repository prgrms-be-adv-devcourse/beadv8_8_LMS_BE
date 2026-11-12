package com.hapbang.auxiliary.search.lecture.app.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 강의 검색 결과 한 줄(#883 L12). 강의 목록 카드에서 썸네일·가격을 뺀 칸이다. 썸네일은 FE가 강의 앱 일괄 API로 채우고, 가격은 강의 가격 칸이
 * 생기면 더한다.
 *
 * @param lectureId      강의 ID
 * @param title          제목(원문)
 * @param sellerUserId   판매자 회원 ID
 * @param sellerNickname 판매자 닉네임. 아직 모르면 null
 * @param averageRating  리뷰 평균(소수 첫째 자리 반올림, 강의 목록과 같음). 리뷰가 없으면 null. 평점순에 쓰는 보정 평균이 아니다
 * @param reviewCount    리뷰 수
 * @param highlights     검색어가 걸린 곳. 검색어가 있으면 늘 있고(걸린 칸이 없으면 칸만 null), 검색어가 없으면 null
 */
public record LectureSearchItem(long lectureId, String title, long sellerUserId, String sellerNickname,
                                Double averageRating, long reviewCount, Highlights highlights) {

    /**
     * 검색어가 걸린 곳(#883 L12). 원문의 HTML 특수 문자는 escape되고 걸린 말만 {@code <em>…</em>}으로 감싼다. 그 밖의 태그는 나오지 않는다.
     * 칸마다 걸리지 않았으면 null(빈 목록)이다.
     */
    @Schema(description = "검색어가 걸린 곳(검색어가 있을 때만). HTML escape된 원문에 걸린 말만 <em>으로 감쌌다(그 밖의 태그 없음)")
    public record Highlights(
            @Schema(description = "제목 전체. 제목에 걸리지 않았으면 null", example = "<em>프리미어</em> 컷편집 입문") String title,
            @Schema(description = "판매자 닉네임 전체. 걸리지 않았으면 null", example = "<em>편집</em>장") String sellerNickname,
            @Schema(description = "소개에서 걸린 곳 주변 조각(약 100자). 걸리지 않았으면 null",
                    example = "…처음 쓰는 분도 <em>프리미어</em>로 브이로그를…") String description,
            @Schema(description = "걸린 콘텐츠 제목들(카드에는 안 보이는 칸이라 왜 나왔는지 보여 줄 때 쓴다)",
                    example = "[\"3강 <em>컷편집</em> 단축키\"]") List<String> contentTitles) {

        public Highlights {
            contentTitles = contentTitles == null ? List.of() : List.copyOf(contentTitles);
        }
    }
}

package com.hapbang.auxiliary.chatbot.in;

import com.hapbang.auxiliary.chatbot.app.dto.LectureRecommendResDto;
import com.hapbang.auxiliary.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Tag(name = "챗봇 강의 추천", description = "로그인 회원 (#718). 구매·장바구니·리뷰 활동과 입력한 관심사로 고른 맞춤 강의. LLM을 부르지 않는다.")
public interface ApiV1ChatbotLectureRecommendSpec {

    @Operation(summary = "맞춤 강의 추천",
            description = "최대 5개, 판매자당 최대 2개. 이미 보유·결제 중·장바구니에 담은 강의와 본인 강의는 빠진다. "
                    + "활동 기록도 관심사도 없으면 인기 강의만 주고 needsInterest=true(관심사를 입력받아 다시 부른다). "
                    + "personalized=false면 맞춤이 아닌 인기 강의다. interest를 넣은 요청은 회원마다 주기당 호출 수를 제한한다"
                    + "(넘으면 429, Retry-After). interest를 넣었는데 지금 관심사로 찾을 수 없으면 503 LECTURE_RECOMMEND_UNAVAILABLE"
                    + "(FE는 다시 시도 안내). 다른 실패는 FE가 추천 영역만 숨긴다.")
    RsData<LectureRecommendResDto> recommend(Long userId,
            @Parameter(description = "관심사(1~100자, 저장하지 않음). 없으면 활동 기록만으로", example = "데이터 분석 입문")
            @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "관심사는 공백만으로 쓸 수 없습니다.") String interest);
}

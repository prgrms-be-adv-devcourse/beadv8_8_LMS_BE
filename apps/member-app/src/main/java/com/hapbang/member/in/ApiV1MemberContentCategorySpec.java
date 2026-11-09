package com.hapbang.member.in;

import java.util.List;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 콘텐츠 분야 API Swagger 명세 (#711). 매핑은 {@link ApiV1MemberContentCategoryController} */
@Tag(name = "콘텐츠 분야", description = "인플루언서가 프로필에 고르는 콘텐츠 분야 목록")
public interface ApiV1MemberContentCategorySpec {

    @Operation(summary = "콘텐츠 분야 목록",
            description = "로그인한 회원. 지금 고를 수 있는 분야 전부를 [{code, name}]으로 준다(페이지 없음). "
                    + "정상 인플루언서가 많이 고른 순이고, 같으면 이름순(영문으로 시작하는 이름이 한글보다 앞). "
                    + "code는 바뀌지 않고 name은 바뀔 수 있다. 내 정보 수정의 contentCategories에는 code를 보낸다. "
                    + "분야 고르는 칸의 자동완성은 이 목록을 한 번 받아 화면에서 거른다. "
                    + "목록에서 빠진(꺼진) 분야도 이미 고른 회원의 프로필에는 계속 보인다.")
    RsData<List<ContentCategoryResponse>> contentCategories();
}

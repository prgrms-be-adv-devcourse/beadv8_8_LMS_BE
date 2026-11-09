package com.hapbang.member.in;

import java.util.List;
import java.util.Map;

import com.hapbang.member.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 프로필 사진 주소 일괄 조회 Swagger 명세 (#770). 매핑은 {@link ApiV1MemberProfileImageController} */
@Tag(name = "회원", description = "내 정보, 인플루언서 목록, 회원 프로필")
public interface ApiV1MemberProfileImageSpec {

    /** 한 번에 물을 수 있는 회원 ID 수 (중복 포함) */
    int MAX_IDS = 100;

    /** 주소는 자리표시자다. 실제 발급한 주소는 서명이 들어 있어 레포에 쓰지 않는다 (docs/deploy/s3.md) */
    String EXAMPLE = """
            {
              "statusCode": 200,
              "message": "프로필 사진 주소를 조회했습니다.",
              "data": {
                "3": "https://<BUCKET>.s3.ap-northeast-2.amazonaws.com/prod/member/profile/3/<uuid>?X-Amz-Algorithm=...&X-Amz-Signature=...",
                "7": null,
                "12": "https://<BUCKET>.s3.ap-northeast-2.amazonaws.com/prod/member/profile/12/<uuid>?X-Amz-Algorithm=...&X-Amz-Signature=..."
              }
            }""";

    @Operation(summary = "프로필 사진 주소 일괄 조회",
            description = "로그인한 회원. 인플루언서 검색 결과처럼 회원 ID만 아는 화면이 사진을 채울 때 부른다 (#770). "
                    + "data는 {회원 ID: 주소}이고 요청한 ID마다 키가 있다(중복은 한 번). "
                    + "사진이 없거나, 없는 회원이거나, 프로필을 볼 수 없는 회원(탈퇴·정지)이면 null. "
                    + "주소는 받은 뒤 최소 10분 쓸 수 있다. 만료되거나 불러오지 못하면(사진을 바꾼 직후의 옛 주소 포함) 다시 조회한다. "
                    + "같은 사진은 한동안(최대 50분) 같은 주소를 주어 브라우저 캐시가 적중하고, 사진을 바꾸면 새 주소가 된다. ids가 0개이거나 100개를 넘으면(중복 포함) 400 INVALID_REQUEST.")
    @ApiResponse(responseCode = "200", description = "회원 ID별 사진 주소",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EXAMPLE)))
    RsData<Map<Long, String>> profileImages(
            @Parameter(description = "회원 ID 목록, 쉼표로 구분 (1~100개)", example = "3,7,12")
            @Size(min = 1, max = MAX_IDS, message = "ids는 1~100개여야 합니다.") List<@NotNull Long> ids);
}

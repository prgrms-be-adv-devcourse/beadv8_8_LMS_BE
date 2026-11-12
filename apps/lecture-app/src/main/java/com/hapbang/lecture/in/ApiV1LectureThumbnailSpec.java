package com.hapbang.lecture.in;

import java.util.List;
import java.util.Map;

import com.hapbang.lecture.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 강의 썸네일 URL 일괄 조회 Swagger 명세 (#885). 매핑은 {@link ApiV1LectureThumbnailController} */
@Tag(name = "강의", description = "강의 등록·수정·삭제·조회, 콘텐츠 시청 URL")
public interface ApiV1LectureThumbnailSpec {

    /** 한 번에 물을 수 있는 강의 ID 수 (중복 포함). 검색 한 페이지 최대 크기와 같다 */
    int MAX_IDS = 100;

    /** 주소는 자리표시자다. 실제 발급한 주소는 서명이 들어 있어 레포에 쓰지 않는다 (docs/deploy/s3.md) */
    String EXAMPLE = """
            {
              "statusCode": 200,
              "message": "강의 썸네일 URL을 조회했습니다.",
              "data": {
                "11": "https://<BUCKET>.s3.ap-northeast-2.amazonaws.com/prod/lecture/LECTURE_THUMBNAIL/<uuid>?X-Amz-Algorithm=...&X-Amz-Expires=3600&X-Amz-Signature=...",
                "12": null
              }
            }""";

    @Operation(summary = "강의 썸네일 URL 일괄 조회",
            description = "비회원도 조회할 수 있다. 강의 검색 결과처럼 강의 ID만 아는 화면이 썸네일을 채울 때 부른다 (#885). "
                    + "data는 {강의 ID: 썸네일 조회 URL}이고 요청한 ID마다 키가 있다(중복은 한 번). "
                    + "판매 중(ON_SALE)인 강의만 URL을 주고, 없는 강의·판매 중이 아닌 강의(검수 대기·반려·삭제)·썸네일이 없거나 "
                    + "볼 수 없는 강의는 null이다. URL은 1시간 동안 쓸 수 있고(강의 목록과 같음) 만료되면 다시 조회한다. "
                    + "ids가 0개이거나 100개를 넘으면(중복 포함) 400 INVALID_REQUEST.")
    @ApiResponse(responseCode = "200", description = "강의 ID별 썸네일 조회 URL",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = EXAMPLE)))
    RsData<Map<Long, String>> getThumbnailUrls(
            @Parameter(description = "강의 ID 목록, 쉼표로 구분 (1~100개)", example = "11,12")
            @Size(min = 1, max = MAX_IDS, message = "ids는 1~100개여야 합니다.") List<@NotNull Long> ids);
}

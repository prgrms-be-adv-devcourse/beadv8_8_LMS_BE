package com.hapbang.shared.lecture.dto;

import java.util.List;
import java.util.Objects;

/**
 * AI 추천용 강의 한 개 (#712, D82). {@code LectureChangedEvent}와 강의 동기화 내부 API가 같은 값을 싣는다.
 * 가격·썸네일·검수 정보는 싣지 않는다.
 *
 * @param id           강의 ID
 * @param sellerUserId 판매자 회원 ID. 본인 강의를 추천에서 빼는 데 쓴다
 * @param title        제목
 * @param description  소개. 없으면 {@code null}
 * @param onSale       추천해도 되는 강의인지. 판단 기준은 강의 모듈이 정한다 (지금은 판매 중이고 삭제되지 않음)
 * @param deleted      삭제(Soft Delete)되었는지
 * @param contents     삭제되지 않은 콘텐츠, 순서대로
 * @param version      강의 원본의 버전. 실린 값이 바뀌면 반드시 오른다(콘텐츠만 바뀌어도)
 */
public record LectureSyncDto(
        Long id,
        Long sellerUserId,
        String title,
        String description,
        boolean onSale,
        boolean deleted,
        List<LectureContentSyncDto> contents,
        long version
) {

    public LectureSyncDto {
        Objects.requireNonNull(id, "id는 필수입니다.");
        Objects.requireNonNull(sellerUserId, "sellerUserId는 필수입니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
        contents = List.copyOf(Objects.requireNonNull(contents, "contents는 필수입니다."));
    }
}

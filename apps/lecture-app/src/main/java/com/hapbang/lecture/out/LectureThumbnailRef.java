package com.hapbang.lecture.out;

/** 판매 중인 강의 하나의 썸네일 파일 ID (#885 썸네일 일괄 조회). 썸네일이 없으면 {@code thumbnailFileId}가 null이다 */
public record LectureThumbnailRef(Long lectureId, Long thumbnailFileId) {
}

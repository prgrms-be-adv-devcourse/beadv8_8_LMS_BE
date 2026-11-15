package com.hapbang.auxiliary.search.lecture.app;

/**
 * 강의 검색 조건(#883). 값은 Facade가 다듬은 뒤다.
 *
 * @param keyword 검색어(제목·콘텐츠 제목·판매자 닉네임·소개). 없으면 null
 * @param sort    순서
 * @param page    페이지 번호(0부터)
 * @param size    페이지 크기
 */
public record LectureSearchCondition(String keyword, LectureSort sort, int page, int size) {

    /** 앞에서 몇 건을 건너뛰는지 */
    public long offset() {
        return (long) page * size;
    }
}

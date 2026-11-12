package com.hapbang.auxiliary.search.lecture.out;

/**
 * 강의 검색 문서에 지금 들어 있는 값(#884). 보정·따라잡기가 원본·사본과 비교해 다른 것만 고칠 때 읽는다.
 *
 * @param lectureId      강의 ID(문서 ID)
 * @param lectureVersion 문서를 쓴 강의 version. 없으면 null(평점·판매자 칸만 있는 문서는 생기지 않지만 읽기는 막지 않는다)
 * @param hidden         숨김(tombstone)인지
 * @param seller         판매자 칸(회원 ID·닉네임·정상 여부·회원 version)
 * @param rating         평점 칸(평균·리뷰 수)
 */
public record IndexedLecture(long lectureId, Long lectureVersion, boolean hidden, Seller seller,
                             LectureRating rating) {
}

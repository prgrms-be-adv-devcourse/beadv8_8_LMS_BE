package com.hapbang.auxiliary.search.lecture.out;

import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

/**
 * 검색이 강의 앱에서 읽는 강의·리뷰 전체(#884 보정·처음 적재, 내부 API {@code docs/api/internal-lecture-sync.md} 4장). 모든 행(판매 중이 아닌
 * 강의, 지운 리뷰 포함)을 ID 오름차순 키셋으로 준다. 테스트는 가짜로 바꿔 끼운다. 실패는 {@code LectureApiCallException}(shared)으로 나간다.
 */
public interface LectureSyncSource {

    /** 강의 한 페이지. afterId가 null이면 처음부터 */
    LectureSyncPageDto lectures(Long afterId, int size);

    /** 리뷰 한 페이지. afterId가 null이면 처음부터 */
    LectureReviewSyncPageDto reviews(Long afterId, int size);
}

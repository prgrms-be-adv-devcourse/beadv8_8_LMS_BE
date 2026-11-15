package com.hapbang.auxiliary.chatbot.out;

import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

/**
 * 강의 원본의 동기화 목록 4종 (#717, 계약 docs/api/internal-lecture-sync.md). 운영은 강의 내부 API({@link LectureApiSyncSource}),
 * 테스트는 가짜로 바꿔 끼운다. 실패는 shared {@code LectureApiCallException}(거절·일시 장애)이다.
 * 모두 이 ID보다 큰 항목을 ID 오름차순으로 size개까지 준다. afterId가 null이면 처음부터다.
 */
public interface LectureSyncSource {

    LectureSyncPageDto lectures(Long afterId, int size);

    LecturePurchaseSyncPageDto purchases(Long afterId, int size);

    CartItemSyncPageDto cartItems(Long afterId, int size);

    LectureReviewSyncPageDto reviews(Long afterId, int size);
}

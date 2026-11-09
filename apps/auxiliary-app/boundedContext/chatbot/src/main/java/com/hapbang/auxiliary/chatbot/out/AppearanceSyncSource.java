package com.hapbang.auxiliary.chatbot.out;

import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

/**
 * 출연 원본의 동기화 목록 (#716). 운영은 출연 내부 API({@link AppearanceApiSyncSource}), 테스트는 가짜로 바꿔 끼운다.
 * 실패는 shared {@code AppearanceApiCallException}(거절·일시 장애)이다.
 */
public interface AppearanceSyncSource {

    /** 이 ID보다 큰 출연을 ID 오름차순으로 size개까지. afterId가 null이면 처음부터 */
    AppearanceSyncPageDto appearances(Long afterId, int size);

    /** 이 ID보다 큰 공개된 리뷰를 ID 오름차순으로 size개까지. afterId가 null이면 처음부터 */
    AppearanceReviewSyncPageDto reviews(Long afterId, int size);
}

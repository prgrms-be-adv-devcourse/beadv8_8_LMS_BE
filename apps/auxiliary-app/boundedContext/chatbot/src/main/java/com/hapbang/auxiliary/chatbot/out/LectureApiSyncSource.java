package com.hapbang.auxiliary.chatbot.out;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;
import com.hapbang.shared.lecture.out.LectureApiClient;

/**
 * 강의 동기화 내부 API로 읽는다 (#717, 계약 docs/api/internal-lecture-sync.md).
 * <p>
 * Client가 없을 때의 처리는 {@link ApiClients}.
 */
@Component
@Import(LectureApiClient.class)
public class LectureApiSyncSource implements LectureSyncSource {

    private final ObjectProvider<LectureApiClient> client;

    public LectureApiSyncSource(ObjectProvider<LectureApiClient> client) {
        this.client = client;
    }

    @Override
    public LectureSyncPageDto lectures(Long afterId, int size) {
        return client().getSyncLectures(afterId, size);
    }

    @Override
    public LecturePurchaseSyncPageDto purchases(Long afterId, int size) {
        return client().getSyncPurchases(afterId, size);
    }

    @Override
    public CartItemSyncPageDto cartItems(Long afterId, int size) {
        return client().getSyncCartItems(afterId, size);
    }

    @Override
    public LectureReviewSyncPageDto reviews(Long afterId, int size) {
        return client().getSyncReviews(afterId, size);
    }

    private LectureApiClient client() {
        return ApiClients.require(client, "lecture");
    }
}

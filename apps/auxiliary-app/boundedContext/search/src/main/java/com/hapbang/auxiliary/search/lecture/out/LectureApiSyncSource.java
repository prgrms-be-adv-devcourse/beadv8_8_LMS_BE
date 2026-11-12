package com.hapbang.auxiliary.search.lecture.out;

import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;
import com.hapbang.shared.lecture.out.LectureApiClient;

/** 강의 동기화 내부 API로 강의·리뷰를 읽는다(#884, D62). 챗봇 강의 싱크(#717)와 같은 API다 */
@Component
@Import(LectureApiClient.class)
class LectureApiSyncSource implements LectureSyncSource {

    private final LectureApiClient client;

    LectureApiSyncSource(LectureApiClient client) {
        this.client = client;
    }

    @Override
    public LectureSyncPageDto lectures(Long afterId, int size) {
        return client.getSyncLectures(afterId, size);
    }

    @Override
    public LectureReviewSyncPageDto reviews(Long afterId, int size) {
        return client.getSyncReviews(afterId, size);
    }
}

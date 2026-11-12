package com.hapbang.auxiliary.search.lecture.out;

import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;
import com.hapbang.shared.lecture.out.LectureApiClient;

/**
 * 강의 동기화 내부 API로 강의·리뷰를 읽는다(#884, D62). 챗봇 강의 싱크(#717)와 같은 API다. 챗봇에 같은 이름의 빈({@code LectureApiSyncSource})이
 * 있어 {@code Search}를 붙였다(같은 앱에서 빈 이름이 겹치면 기동하지 못한다).
 */
@Component
@Import(LectureApiClient.class)
class SearchLectureApiSyncSource implements LectureSyncSource {

    private final LectureApiClient client;

    SearchLectureApiSyncSource(LectureApiClient client) {
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

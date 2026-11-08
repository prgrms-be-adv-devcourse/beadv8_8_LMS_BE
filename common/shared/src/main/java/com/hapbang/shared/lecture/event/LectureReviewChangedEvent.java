package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;

/**
 * 강의 리뷰가 바뀌었다 (#712, D82). 작성·수정·삭제와, 이의 제기 인정으로 평점 반영 여부({@code counted})가 바뀔 때 전체 상태를 싣는다.
 * key는 {@code lecture-review:<리뷰 ID>}다.
 *
 * @param review 바뀐 뒤의 리뷰
 */
@Externalized(LectureEventTopic.NAME + "::#{'lecture-review:' + #this.review().id()}")
public record LectureReviewChangedEvent(LectureReviewSyncDto review) {
}

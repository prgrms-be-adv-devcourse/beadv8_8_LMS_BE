package com.hapbang.shared.lecture.event;

import org.springframework.modulith.events.Externalized;

import com.hapbang.shared.lecture.dto.LectureSyncDto;

/**
 * 강의가 바뀌었다 (#712, D82). 등록·정보 수정·콘텐츠 교체·검수 승인·반려·재검수 신청·삭제 뒤의 전체 상태를 싣는다.
 * 받는 쪽은 {@code version}이 크면 반영하고 작으면 버린다. key는 {@code lecture:<강의 ID>}다.
 *
 * @param lecture 바뀐 뒤의 강의
 */
@Externalized(LectureEventTopic.NAME + "::#{'lecture:' + #this.lecture().id()}")
public record LectureChangedEvent(LectureSyncDto lecture) {
}

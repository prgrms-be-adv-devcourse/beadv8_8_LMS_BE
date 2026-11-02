package com.hapbang.lecture.app;

import com.hapbang.shared.lecture.domain.LectureObjectionCommandType;
import com.hapbang.shared.lecture.domain.LectureObjectionFailureCode;

/**
 * 이의 제기 명령 하나를 처리한 결과 (#278, D71).
 *
 * @param commandId   명령 ID
 * @param commandType 명령 종류
 * @param inquiryId   이의 제기 문의 ID
 * @param orderItemId 주문 항목 ID
 * @param failureCode 거절 사유. 반영했으면 {@code null}
 * @param replayed    이미 처리한 명령이라 처음 결과를 재현했는지. 재현이면 항목도 이벤트도 바꾸지 않았다
 */
public record LectureObjectionCommandResult(String commandId, LectureObjectionCommandType commandType, Long inquiryId,
                                            Long orderItemId, LectureObjectionFailureCode failureCode,
                                            boolean replayed) {

    public boolean succeeded() {
        return failureCode == null;
    }
}

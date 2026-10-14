package com.hapbang.lecture.in;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.LectureObjectionFacade;
import com.hapbang.shared.lecture.port.LectureObjectionPort;

import lombok.RequiredArgsConstructor;

// 고객센터가 shared의 LectureObjectionPort로 보내는 요청을 LectureObjectionFacade에 넘긴다.
// 트랜잭션은 열지 않는다. Facade의 @Transactional(REQUIRED)이 호출한 쪽 트랜잭션에 참여한다.
// 접수 불가 예외(LectureObjectionRefusedException)와 billing 예외는 잡거나 바꾸지 않고 그대로 전파한다.
@Component
@RequiredArgsConstructor
class LectureObjectionPortAdapter implements LectureObjectionPort {

    private final LectureObjectionFacade lectureObjectionFacade;

    @Override
    public void fileObjection(Long orderItemId, Long buyerUserId, Long inquiryId, String reason) {
        lectureObjectionFacade.fileObjection(orderItemId, buyerUserId, inquiryId, reason);
    }

    @Override
    public void resolveObjection(Long orderItemId, Long inquiryId, boolean accepted, Long adminUserId) {
        lectureObjectionFacade.resolveObjection(orderItemId, inquiryId, accepted, adminUserId);
    }
}

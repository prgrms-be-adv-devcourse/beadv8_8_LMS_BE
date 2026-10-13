package com.hapbang.lecture.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.LectureUserFacade;
import com.hapbang.shared.member.event.UserJoinedEvent;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트를 받아 강의 모듈의 회원 복제본을 동기화한다.
 * <p>
 * {@link ApplicationModuleListener}: 회원 트랜잭션이 커밋된 뒤 별도 트랜잭션에서 비동기로 처리한다.
 * 처리하지 못한 이벤트는 Modulith 발행 기록에 남아 재시작 시 다시 처리된다.
 * 복제본은 가입 이벤트로만 생성한다. 가입보다 먼저 온 변경 이벤트는 실패로 남았다가, 재시작 시 다시 처리된다.
 */
// TODO(event): 변경 이벤트끼리 순서가 뒤바뀌면(실패한 변경 이벤트의 재처리 등) 옛 정보가 반영될 수 있다.
//  UserDto에 버전(또는 수정 시각)을 넣고 ReplicaUser가 더 새로운 이벤트만 반영하도록 팀에 제안한다(#56).
@Component
@RequiredArgsConstructor
public class LectureMemberEventListener {

    private final LectureUserFacade lectureUserFacade;

    @ApplicationModuleListener
    public void on(UserJoinedEvent event) {
        lectureUserFacade.syncJoined(event.user());
    }

    @ApplicationModuleListener
    public void on(UserModifiedEvent event) {
        lectureUserFacade.syncModified(event.user());
    }
}

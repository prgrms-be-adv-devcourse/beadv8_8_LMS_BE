package com.hapbang.lecture.in;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.app.LectureUserSyncService;
import com.hapbang.shared.member.event.UserJoinedEvent;

import lombok.RequiredArgsConstructor;

/** 회원 이벤트를 받아 강의 모듈의 회원 복제본을 동기화한다. */
@Component
@RequiredArgsConstructor
public class LectureMemberEventListener {

    private final LectureUserSyncService lectureUserSyncService;

    @ApplicationModuleListener
    public void on(UserJoinedEvent event) {
        lectureUserSyncService.sync(event.user());
    }
}

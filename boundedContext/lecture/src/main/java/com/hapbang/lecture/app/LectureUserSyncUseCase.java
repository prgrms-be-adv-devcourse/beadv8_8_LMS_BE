package com.hapbang.lecture.app;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/** 회원 정보로 강의 모듈의 회원 복제본을 동기화한다. */
@Service
@RequiredArgsConstructor
public class LectureUserSyncUseCase {

    private final LectureUserRepository lectureUserRepository;

    /** 같은 회원 정보가 재전달돼도 복제본을 중복 생성하지 않는다. */
    public void sync(UserDto user) {
        lectureUserRepository.findById(user.id())
                .ifPresentOrElse(
                        replica -> replica.syncFrom(user),
                        () -> lectureUserRepository.save(LectureUser.from(user))
                );
    }
}

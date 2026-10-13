package com.hapbang.lecture.app;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 정보로 강의 모듈의 회원 복제본을 동기화한다.
 * <ul>
 *     <li>복제본은 가입 정보로만 생성한다. 이미 있으면 무시한다(같은 가입 이벤트의 재처리).</li>
 *     <li>변경 정보는 있는 복제본만 갱신한다. 없으면(가입 이벤트가 아직 처리되지 않음) 예외를 던져 처리 실패로 남기고,
 *     가입 이벤트가 처리된 뒤 다시 처리될 때 반영한다.</li>
 * </ul>
 * 생성하는 쪽이 가입 하나라, 가입·변경이 동시에 생성하다 변경 정보를 덮어쓰는 일이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureUserSyncUseCase {

    private final LectureUserRepository lectureUserRepository;

    public void createIfAbsent(UserDto user) {
        if (lectureUserRepository.existsById(user.id())) {
            log.debug("이미 복제된 강의 회원이라 가입 정보를 무시한다 userId={}", user.id());
            return;
        }
        lectureUserRepository.save(LectureUser.from(user));
    }

    public void update(UserDto user) {
        LectureUser replica = lectureUserRepository.findById(user.id())
                .orElseThrow(() -> new LectureException(LectureErrorCode.LECTURE_USER_NOT_FOUND));
        replica.syncFrom(user);
    }
}

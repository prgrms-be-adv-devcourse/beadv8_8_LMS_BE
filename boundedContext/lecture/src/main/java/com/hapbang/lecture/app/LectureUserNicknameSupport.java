package com.hapbang.lecture.app;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 강의 회원 복제본에서 판매자·리뷰 작성자 닉네임을 읽는다. 회원 복제가 아직 도착하지 않은 회원의 닉네임은 null이다.
 */
@Component
@RequiredArgsConstructor
class LectureUserNicknameSupport {

    private static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

    private final LectureUserRepository lectureUserRepository;

    String of(Long userId) {
        return lectureUserRepository.findById(userId)
                .map(LectureUserNicknameSupport::display)
                .orElse(null);
    }

    /** 회원 복제가 아직 도착하지 않은 회원은 빠진다 */
    Map<Long, String> of(Collection<Long> userIds) {
        return lectureUserRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(LectureUser::getId, LectureUserNicknameSupport::display));
    }

    private static String display(LectureUser user) {
        return user.isWithdrawn() ? WITHDRAWN_NICKNAME : user.getNickname();
    }
}

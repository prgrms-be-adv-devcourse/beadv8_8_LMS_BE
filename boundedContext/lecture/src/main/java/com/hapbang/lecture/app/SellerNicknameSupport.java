package com.hapbang.lecture.app;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 강의 회원 복제본에서 판매자 닉네임을 읽는다. 회원 복제가 아직 도착하지 않은 판매자의 닉네임은 null이다.
 */
@Component
@RequiredArgsConstructor
class SellerNicknameSupport {

    private static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

    private final LectureUserRepository lectureUserRepository;

    String of(Long sellerUserId) {
        return lectureUserRepository.findById(sellerUserId)
                .map(SellerNicknameSupport::display)
                .orElse(null);
    }

    /** 회원 복제가 아직 도착하지 않은 판매자는 빠진다 */
    Map<Long, String> of(Collection<Long> sellerUserIds) {
        return lectureUserRepository.findAllById(sellerUserIds).stream()
                .collect(Collectors.toMap(LectureUser::getId, SellerNicknameSupport::display));
    }

    private static String display(LectureUser user) {
        return user.isWithdrawn() ? WITHDRAWN_NICKNAME : user.getNickname();
    }
}

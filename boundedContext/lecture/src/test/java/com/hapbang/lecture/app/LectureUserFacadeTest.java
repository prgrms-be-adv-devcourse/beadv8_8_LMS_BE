package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class LectureUserFacadeTest {

    @Autowired
    private LectureUserFacade lectureUserFacade;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Test
    void 가입_정보로_강의_회원을_만든다() {
        lectureUserFacade.syncJoined(new UserDto(1L, "구매자", Role.USER, UserStatus.ACTIVE));

        LectureUser found = lectureUserRepository.findById(1L).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("구매자");
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 이미_있는_강의_회원에게_온_가입_정보는_무시한다() {
        lectureUserFacade.syncJoined(new UserDto(1L, "가입닉네임", Role.USER, UserStatus.ACTIVE));
        lectureUserFacade.syncModified(new UserDto(1L, "바뀐닉네임", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE));

        lectureUserFacade.syncJoined(new UserDto(1L, "가입닉네임", Role.USER, UserStatus.ACTIVE));

        assertThat(lectureUserRepository.count()).isEqualTo(1);
        LectureUser found = lectureUserRepository.findById(1L).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("바뀐닉네임");
        assertThat(found.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
    }

    @Test
    void 변경_정보로_닉네임_역할_상태를_갱신한다() {
        lectureUserFacade.syncJoined(new UserDto(1L, "구매자", Role.USER, UserStatus.ACTIVE));

        lectureUserFacade.syncModified(new UserDto(1L, "판매자", Role.VERIFIED_INFLUENCER, UserStatus.SUSPENDED));

        LectureUser found = lectureUserRepository.findById(1L).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("판매자");
        assertThat(found.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void 강의_회원이_없으면_변경_정보로_만들지_않고_실패한다() {
        assertThatThrownBy(() -> lectureUserFacade.syncModified(
                new UserDto(1L, "바뀐닉네임", Role.INFLUENCER, UserStatus.ACTIVE)))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.LECTURE_USER_NOT_FOUND);

        assertThat(lectureUserRepository.findById(1L)).isEmpty();
    }
}

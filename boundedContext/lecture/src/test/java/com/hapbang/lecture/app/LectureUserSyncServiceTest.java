package com.hapbang.lecture.app;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

@IntegrationTest
class LectureUserSyncServiceTest {

    @Autowired
    private LectureUserSyncService lectureUserSyncService;

    @Autowired
    private LectureUserRepository lectureUserRepository;

    @Test
    void 처음_받은_회원_정보면_강의_회원을_만든다() {
        lectureUserSyncService.sync(new UserDto(1L, "구매자", Role.USER, UserStatus.ACTIVE));

        LectureUser found = lectureUserRepository.findById(1L).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("구매자");
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 이미_있는_회원이면_새로_만들지_않고_정보만_갱신한다() {
        lectureUserSyncService.sync(new UserDto(1L, "구매자", Role.USER, UserStatus.ACTIVE));

        lectureUserSyncService.sync(new UserDto(1L, "판매자", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE));

        assertThat(lectureUserRepository.count()).isEqualTo(1);
        LectureUser found = lectureUserRepository.findById(1L).orElseThrow();
        assertThat(found.getNickname()).isEqualTo("판매자");
        assertThat(found.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
    }
}

package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;

import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.out.LectureUserRepository;
import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.domain.OAuthProvider;
import com.hapbang.member.domain.User;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureUserEventIntegrationTest {

    @Autowired
    MemberFacade memberFacade;

    @Autowired
    LectureUserRepository lectureUserRepository;

    @Test
    @DisplayName("회원 가입 이벤트를 받으면 강의 회원 복제본을 저장한다")
    void replicateJoinedUser() {
        User user = memberFacade.oauthLogin(
                OAuthProvider.GOOGLE,
                "lecture-user-event-sub",
                "lecture-user-event@example.com",
                "강의회원"
        );

        await().untilAsserted(() -> assertThat(lectureUserRepository.findById(user.getId()))
                .get()
                .satisfies(replica -> {
                    assertThat(replica.getNickname()).isEqualTo("강의회원");
                    assertThat(replica.getRole()).isEqualTo(user.getRole());
                    assertThat(replica.getStatus()).isEqualTo(user.getStatus());
                }));
    }

    @Test
    @DisplayName("회원 정보 변경 이벤트를 받으면 강의 회원 복제본을 갱신한다")
    void replicateModifiedUser() {
        User user = memberFacade.oauthLogin(
                OAuthProvider.GOOGLE,
                "lecture-user-modify-sub",
                "lecture-user-modify@example.com",
                "변경전닉네임"
        );
        await().untilAsserted(() -> assertThat(lectureUserRepository.findById(user.getId())).isPresent());
        long countBefore = lectureUserRepository.count();

        memberFacade.updateProfile(user.getId(), "변경후닉네임");

        await().untilAsserted(() -> assertThat(lectureUserRepository.findById(user.getId()))
                .get()
                .extracting(LectureUser::getNickname)
                .isEqualTo("변경후닉네임"));
        assertThat(lectureUserRepository.count()).isEqualTo(countBefore);
    }
}

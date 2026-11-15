package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.testsupport.RepositoryTest;

import jakarta.persistence.EntityManager;

@RepositoryTest
class UserRepositoryTest {

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("회원을 저장하면 ID와 생성일·수정일이 채워진다")
    void save() {
        User saved = userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 이메일로 저장하면 유니크 제약 위반이다")
    void save_duplicateEmail() {
        userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(User.join("tester1@example.com", "tester2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("이메일 중복 확인은 대소문자를 무시한다 (구글 가입 이메일에 대문자가 섞여도 찾는다, #499)")
    void findByEmailIgnoreCase() {
        User saved = userRepository.saveAndFlush(User.join("Tester1@Example.com", "tester1"));

        assertThat(userRepository.existsByEmailIgnoreCase("tester1@example.com")).isTrue();
        assertThat(userRepository.findByEmailIgnoreCase("TESTER1@EXAMPLE.COM"))
                .hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
        assertThat(userRepository.existsByEmailIgnoreCase("other@example.com")).isFalse();
    }

    @Test
    @DisplayName("콘텐츠 분야는 고른 순서로 저장되고, 순서만 바꿔도 유일 제약에 걸리지 않고 저장되며 version이 오른다 (#711)")
    void contentCategories_orderAndVersion() {
        User user = userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));
        user.changeContentCategories(List.of("GAME", "MAPLESTORY", "MUKBANG"));
        userRepository.saveAndFlush(user);
        Long before = reload(user).getVersion();

        User loaded = reload(user);
        loaded.changeContentCategories(List.of("MUKBANG", "GAME", "MAPLESTORY"));
        userRepository.saveAndFlush(loaded);

        User reloaded = reload(user);
        assertThat(reloaded.getContentCategories()).containsExactly("MUKBANG", "GAME", "MAPLESTORY");
        assertThat(reloaded.getVersion()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("지금과 같은 콘텐츠 분야·자기소개를 다시 넣으면 version이 오르지 않는다 (#711)")
    void contentCategories_sameValueKeepsVersion() {
        User user = userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));
        user.changeContentCategories(List.of("GAME", "MAPLESTORY"));
        user.changeBio("메이플 위주");
        userRepository.saveAndFlush(user);
        Long before = reload(user).getVersion();

        User loaded = reload(user);
        loaded.changeContentCategories(List.of("GAME", "MAPLESTORY"));
        loaded.changeBio("메이플 위주");
        userRepository.saveAndFlush(loaded);

        assertThat(reload(user).getVersion()).isEqualTo(before);
    }

    @Test
    @DisplayName("자기소개를 바꾸면 저장되고 version이 오른다 (#711)")
    void bio_saved() {
        User user = userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));
        Long before = user.getVersion();

        User loaded = reload(user);
        loaded.changeBio("메이플 위주로 방송해요");
        userRepository.saveAndFlush(loaded);

        User reloaded = reload(user);
        assertThat(reloaded.getBio()).isEqualTo("메이플 위주로 방송해요");
        assertThat(reloaded.getVersion()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("분야 목록에 없는 코드는 저장할 수 없다(FK, #711)")
    void contentCategories_unknownCode() {
        User user = userRepository.saveAndFlush(User.join("tester1@example.com", "tester1"));
        user.changeContentCategories(List.of("NO_SUCH_CATEGORY"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 영속성 컨텍스트를 비우고 DB에서 다시 읽는다 (분야는 LAZY라 읽어 둔다) */
    private User reload(User user) {
        entityManager.clear();
        User found = userRepository.findById(user.getId()).orElseThrow();
        found.getContentCategories();
        return found;
    }

    private static final List<Role> INFLUENCER_ROLES = List.of(Role.INFLUENCER, Role.VERIFIED_INFLUENCER);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Test
    @DisplayName("인플루언서 목록에는 인플루언서·인증된 인플루언서만 나오고 유저·관리자는 빠진다")
    void findByRoleInAndStatus_roles() {
        save("user1", Role.USER, UserStatus.ACTIVE);
        save("admin1", Role.ADMIN, UserStatus.ACTIVE);
        User influencer = save("influencer1", Role.INFLUENCER, UserStatus.ACTIVE);
        User verified = save("verified1", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE);

        Page<User> page = userRepository.findByRoleInAndStatus(INFLUENCER_ROLES, UserStatus.ACTIVE,
                PageRequest.of(0, 10, NEWEST_FIRST));

        assertThat(page.getContent()).extracting(User::getId)
                .containsExactlyInAnyOrder(influencer.getId(), verified.getId());
    }

    @Test
    @DisplayName("탈퇴·정지한 인플루언서는 목록에서 빠진다")
    void findByRoleInAndStatus_status() {
        User active = save("active1", Role.INFLUENCER, UserStatus.ACTIVE);
        save("withdrawn1", Role.INFLUENCER, UserStatus.WITHDRAWN);
        save("suspended1", Role.INFLUENCER, UserStatus.SUSPENDED);

        Page<User> page = userRepository.findByRoleInAndStatus(INFLUENCER_ROLES, UserStatus.ACTIVE,
                PageRequest.of(0, 10, NEWEST_FIRST));

        assertThat(page.getContent()).extracting(User::getId).containsExactly(active.getId());
    }

    @Test
    @DisplayName("최신 가입순으로 페이지를 나누고 전체 개수를 함께 준다")
    void findByRoleInAndStatus_paging() {
        User first = save("first1", Role.INFLUENCER, UserStatus.ACTIVE);
        User second = save("second1", Role.INFLUENCER, UserStatus.ACTIVE);
        User third = save("third1", Role.INFLUENCER, UserStatus.ACTIVE);

        Page<User> firstPage = userRepository.findByRoleInAndStatus(INFLUENCER_ROLES, UserStatus.ACTIVE,
                PageRequest.of(0, 2, NEWEST_FIRST));
        Page<User> secondPage = userRepository.findByRoleInAndStatus(INFLUENCER_ROLES, UserStatus.ACTIVE,
                PageRequest.of(1, 2, NEWEST_FIRST));

        assertThat(firstPage.getContent()).extracting(User::getId).containsExactly(third.getId(), second.getId());
        assertThat(secondPage.getContent()).extracting(User::getId).containsExactly(first.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
    }

    /** 탈퇴·정지 기능(F7 등)이 아직 없어 상태는 테스트에서만 직접 바꾼다 */
    private User save(String nickname, Role role, UserStatus status) {
        User user = User.join(nickname + "@example.com", nickname);
        user.changeRole(role);
        ReflectionTestUtils.setField(user, "status", status);
        return userRepository.saveAndFlush(user);
    }
}

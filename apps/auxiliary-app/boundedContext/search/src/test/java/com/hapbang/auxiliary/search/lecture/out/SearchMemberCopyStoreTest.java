package com.hapbang.auxiliary.search.lecture.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.RepositoryTest;

/** 회원 사본(#882 Q1). 모듈 SQL로 만든 search.member_copy에 PostgreSQL 컨테이너로 쓴다 */
@RepositoryTest
@Import(SearchMemberCopyStore.class)
class SearchMemberCopyStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 15, 0);

    @Autowired
    private SearchMemberCopyStore store;

    @Test
    @DisplayName("처음 받은 회원은 넣고 판매자 정보가 바뀐 것으로 돌려준다")
    void insert() {
        assertThat(store.apply(user(7L, "강사", UserStatus.ACTIVE, 1L), NOW)).contains(new Seller(7L, "강사", true));
        assertThat(store.findSeller(7L)).contains(new Seller(7L, "강사", true));
    }

    @Test
    @DisplayName("사본에 없는 회원은 비어 있다")
    void notFound() {
        assertThat(store.findSeller(99L)).isEmpty();
    }

    @Test
    @DisplayName("더 새 version의 닉네임·상태 변경은 반영하고 바뀐 판매자 정보를 돌려준다")
    void changed() {
        store.apply(user(7L, "강사", UserStatus.ACTIVE, 1L), NOW);

        assertThat(store.apply(user(7L, "새강사", UserStatus.ACTIVE, 2L), NOW)).contains(new Seller(7L, "새강사", true));
        assertThat(store.apply(user(7L, "새강사", UserStatus.SUSPENDED, 3L), NOW))
                .contains(new Seller(7L, "새강사", false));
        assertThat(store.findSeller(7L)).contains(new Seller(7L, "새강사", false));
    }

    @Test
    @DisplayName("닉네임·상태가 같으면 version만 올리고 비어 있다(강의 문서를 고칠 일이 없다)")
    void versionOnly() {
        store.apply(user(7L, "강사", UserStatus.ACTIVE, 1L), NOW);

        assertThat(store.apply(new UserDto(7L, "강사", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 2L,
                List.of("GAME"), "자기소개"), NOW)).isEmpty();
        assertThat(store.findSeller(7L)).contains(new Seller(7L, "강사", true));
    }

    @Test
    @DisplayName("순서가 뒤바뀌어 늦게 온 옛 version은 아무것도 바꾸지 않고 비어 있다")
    void stale() {
        store.apply(user(7L, "정지된", UserStatus.SUSPENDED, 5L), NOW);

        assertThat(store.apply(user(7L, "옛닉네임", UserStatus.ACTIVE, 4L), NOW)).isEmpty();
        assertThat(store.findSeller(7L)).contains(new Seller(7L, "정지된", false));
    }

    @Test
    @DisplayName("같은 version을 다시 받으면(재시도·중복) 사본은 그대로 두고 사본의 판매자 정보를 다시 돌려준다(강의 문서를 다시 맞추게)")
    void sameVersionAgain() {
        store.apply(user(7L, "정지된", UserStatus.SUSPENDED, 5L), NOW);

        assertThat(store.apply(user(7L, "정지된", UserStatus.SUSPENDED, 5L), NOW))
                .contains(new Seller(7L, "정지된", false));
        assertThat(store.apply(user(7L, "같은버전다른값", UserStatus.ACTIVE, 5L), NOW))
                .contains(new Seller(7L, "정지된", false));
        assertThat(store.findSeller(7L)).contains(new Seller(7L, "정지된", false));
    }

    @Test
    @DisplayName("version이 없는 정보는 사본에도 version이 없을 때만 반영하고, 그때는 재시도와 가릴 수 없어 늘 판매자 정보를 돌려준다(UserDto 규칙)")
    void nullVersion() {
        assertThat(store.apply(user(7L, "버전없음", UserStatus.ACTIVE, null), NOW))
                .contains(new Seller(7L, "버전없음", true));
        assertThat(store.apply(user(7L, "또버전없음", UserStatus.ACTIVE, null), NOW))
                .contains(new Seller(7L, "또버전없음", true));
        // 같은 정보를 다시 받아도(재시도) 강의 문서를 다시 맞추게 판매자 정보를 돌려준다
        assertThat(store.apply(user(7L, "또버전없음", UserStatus.ACTIVE, null), NOW))
                .contains(new Seller(7L, "또버전없음", true));

        store.apply(user(7L, "버전있음", UserStatus.ACTIVE, 1L), NOW);

        assertThat(store.apply(user(7L, "옛이벤트", UserStatus.ACTIVE, null), NOW)).isEmpty();
        assertThat(store.findSeller(7L)).contains(new Seller(7L, "버전있음", true));
    }

    @Test
    @DisplayName("회원 ID·상태가 없는 정보는 받지 않는다")
    void requiresIdAndStatus() {
        assertThatThrownBy(() -> store.apply(user(null, "닉", UserStatus.ACTIVE, 1L), NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.apply(user(7L, "닉", null, 1L), NOW))
                .isInstanceOf(NullPointerException.class);
    }

    private static UserDto user(Long id, String nickname, UserStatus status, Long version) {
        return new UserDto(id, nickname, Role.VERIFIED_INFLUENCER, status, version);
    }
}

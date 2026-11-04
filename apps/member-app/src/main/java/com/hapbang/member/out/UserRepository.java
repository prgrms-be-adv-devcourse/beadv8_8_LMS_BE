package com.hapbang.member.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 회원 저장소 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * 가입할 때 이메일 중복 확인 (#499). 구글 가입 이메일은 받은 그대로 저장돼 대소문자가 섞일 수 있고
     * users.email 유일 제약은 대소문자를 구별하므로, 대소문자를 무시하고 찾는다
     */
    boolean existsByEmailIgnoreCase(String email);

    /** 구글 가입 때 같은 이메일 회원 확인 (#499). 대소문자를 무시한다 (existsByEmailIgnoreCase와 같은 이유) */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * 닉네임 중복 확인 (#646). 대소문자를 무시하고 탈퇴 회원도 포함한다.
     * {@code lower(nickname)}으로 비교해 유일 인덱스(uk_users_nickname_lower)를 탄다
     */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname)")
    boolean existsByNicknameLower(@Param("nickname") String nickname);

    /** 닉네임 중복 확인에서 이 회원 자신은 뺀다 (내 정보 수정: 내 닉네임 그대로거나 대소문자만 바꾸면 통과) */
    @Query("select count(u) > 0 from User u where lower(u.nickname) = lower(:nickname) and u.id <> :userId")
    boolean existsByNicknameLowerExcept(@Param("nickname") String nickname, @Param("userId") Long userId);

    /**
     * 마스킹할 회원 ID (#594): 탈퇴했고, 아직 마스킹하지 않았고, 복구 기한이 지난 회원을 탈퇴 시각 순으로.
     * 복구 기한은 탈퇴 시각 + 30일이고, 기한 안에 지갑 장애로 복구가 실패했으면(restoreFailedAt) 7일을 더한다
     * ({@code User.restorableUntil}과 같은 규칙). 기준 시각은 부르는 쪽이 계산해 넘긴다
     *
     * @param deadlineCutoff 지금 − 30일. 탈퇴 시각이 이보다 앞서야 한다
     * @param graceCutoff    지금 − 37일. 복구 실패 기록이 있으면 탈퇴 시각이 이보다 앞서야 한다
     */
    @Query("""
            select u.id from User u
            where u.status = com.hapbang.shared.member.domain.UserStatus.WITHDRAWN
              and u.maskedAt is null
              and u.deletedAt < :deadlineCutoff
              and (u.restoreFailedAt is null or u.deletedAt < :graceCutoff)
            order by u.deletedAt, u.id""")
    List<Long> findMaskingTargetIds(@Param("deadlineCutoff") LocalDateTime deadlineCutoff,
                                    @Param("graceCutoff") LocalDateTime graceCutoff, Limit limit);

    /** 역할·상태로 회원 목록 (인플루언서 목록) */
    Page<User> findByRoleInAndStatus(Collection<Role> roles, UserStatus status, Pageable pageable);

    /** 내부 회원 목록(키셋): afterId보다 큰 ID를 오름차순으로 limit개. 모든 역할·상태 포함 (#303) */
    List<User> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);
}

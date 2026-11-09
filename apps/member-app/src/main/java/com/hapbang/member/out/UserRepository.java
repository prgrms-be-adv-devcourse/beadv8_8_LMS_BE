package com.hapbang.member.out;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.User;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;

/** 회원 저장소 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 회원 한 명을 읽을 때 콘텐츠 분야도 조인으로 함께 읽는다 (#711). 분야는 LAZY라 트랜잭션이 끝난 뒤
     * (컨트롤러 응답, open-in-view 꺼짐) 읽으면 실패하므로, 한 명을 읽는 기본 경로({@code MemberSupport.findUser})에서 미리 채운다
     */
    @Override
    @EntityGraph(attributePaths = "contentCategories")
    Optional<User> findById(Long id);

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
     * 마스킹 후보 회원 ID (#594): 탈퇴했고, 아직 마스킹하지 않았고, 탈퇴하고 30일이 지난 회원을 ID 순으로 {@code afterId} 다음부터.
     * 복구 유예(7일) 같은 최종 판단은 하지 않는다. 후보를 넓게 고르고 {@code User.canRestore}가 한 곳에서 정한다(규칙이 두 곳에 나뉘지 않게).
     * ID 순으로 이어 읽어, 실패한 회원이 앞에 남아도 다음 회원으로 넘어간다
     *
     * @param deadlineCutoff 지금 − 30일 − 마스킹 여유. 탈퇴 시각이 이보다 앞서야 한다
     */
    @Query("""
            select u.id from User u
            where u.status = com.hapbang.shared.member.domain.UserStatus.WITHDRAWN
              and u.maskedAt is null
              and u.deletedAt < :deadlineCutoff
              and u.id > :afterId
            order by u.id""")
    List<Long> findMaskingCandidateIds(@Param("deadlineCutoff") LocalDateTime deadlineCutoff,
                                       @Param("afterId") long afterId, Limit limit);

    /** 역할·상태로 회원 목록 (인플루언서 목록) */
    Page<User> findByRoleInAndStatus(Collection<Role> roles, UserStatus status, Pageable pageable);

    /** 내부 회원 목록(키셋): afterId보다 큰 ID를 오름차순으로 limit개. 모든 역할·상태 포함 (#303) */
    List<User> findByIdGreaterThanOrderByIdAsc(Long afterId, Limit limit);

    /**
     * 프로필 사진 key 일괄 조회 (#770). 정상 회원 중 사진이 있는 회원만 쿼리 한 번으로 읽는다.
     * 정상이 아닌 회원은 프로필 조회(MemberSupport.findActiveUser)와 같이 빠진다
     */
    @Query("""
            select new com.hapbang.member.out.ProfileImageKey(u.id, u.profileImageKey) from User u
            where u.id in :ids
              and u.status = com.hapbang.shared.member.domain.UserStatus.ACTIVE
              and u.profileImageKey is not null""")
    List<ProfileImageKey> findActiveProfileImageKeys(@Param("ids") Collection<Long> ids);

    /**
     * 콘텐츠 분야별로 정상 인플루언서(인플루언서·인증된 인플루언서 + ACTIVE)가 고른 수 (#711). 아무도 고르지 않은 분야는 없다.
     * 분야 목록 API의 순서(고른 수 많은 순)에 쓴다. user_content_category의 code 인덱스로 센다
     */
    @Query("""
            select new com.hapbang.member.out.ContentCategoryCount(code, count(u))
            from User u join u.contentCategories code
            where u.role in (com.hapbang.shared.member.domain.Role.INFLUENCER,
                             com.hapbang.shared.member.domain.Role.VERIFIED_INFLUENCER)
              and u.status = com.hapbang.shared.member.domain.UserStatus.ACTIVE
            group by code""")
    List<ContentCategoryCount> countInfluencersByContentCategory();
}

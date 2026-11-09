package com.hapbang.member.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.BatchSize;

import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.SourceUser;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원 원본 */
@Entity
@Table(name = "users", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends SourceUser {

    /** 탈퇴 뒤 복구할 수 있는 기간 (#593). 30일 뒤 식별 정보 마스킹(#594)도 이 기간을 쓴다 (policy.md 삭제 정책) */
    public static final Duration RESTORE_PERIOD = Duration.ofDays(30);

    /**
     * 복구 유예 (#594). 복구 기한 안에 복구를 눌렀는데 지갑 장애로 실패했으면 기한과 마스킹을 이만큼 미룬다.
     * 회원 잘못이 아닌 장애로 마지막 날 복구를 놓치지 않게 한다
     */
    public static final Duration RESTORE_GRACE = Duration.ofDays(7);

    /**
     * 마스킹한 닉네임 앞부분 (#594). 뒤에 회원 ID를 붙여 마스킹 값끼리 겹치지 않게 한다(닉네임 유일 인덱스, #646).
     * {@code #}은 닉네임 규칙에 없는 글자라 사용자가 이 값을 닉네임으로 쓸 수 없다(탈퇴 회원 사칭 방지)
     */
    public static final String MASKED_NICKNAME_PREFIX = "탈퇴회원#";

    @Column(unique = true)
    private String email;

    private String profileImageKey;

    private LocalDateTime deletedAt;

    /** 복구 기한 안에 지갑 장애로 복구가 처음 실패한 시각 (#594). 있으면 복구 기한이 {@link #RESTORE_GRACE}만큼 늘어난다 */
    private LocalDateTime restoreFailedAt;

    /** 개인정보를 마스킹한 시각 (#594). 있으면 복구할 수 없다 */
    private LocalDateTime maskedAt;

    /** 자기소개 (#711). 없으면 null */
    @Column(length = Bio.MAX_LENGTH)
    private String bio;

    /**
     * 콘텐츠 분야 코드 (#711). 고른 순서를 sort_order로 지킨다. 바뀌면 Hibernate가 {@link #version}을 올린다.
     * 필요할 때 읽고(LAZY), 여러 회원을 읽은 뒤 분야를 읽으면 100명씩 묶어 읽는다(N+1 방지).
     * 바꿀 때는 새 목록으로 통째로 갈아 끼운다(행을 지우고 다시 넣어 (user_id, code) 유일 제약에 걸리지 않게)
     */
    @ElementCollection
    @CollectionTable(name = "user_content_category", schema = "members", joinColumns = @JoinColumn(name = "user_id"),
            uniqueConstraints = @UniqueConstraint(name = "uk_user_content_category_code",
                    columnNames = {"user_id", "code"}))
    @OrderColumn(name = "sort_order")
    @Column(name = "code", nullable = false, length = 50)
    @BatchSize(size = 100)
    @Getter(AccessLevel.NONE)
    private List<String> contentCategories = new ArrayList<>();

    /** 낙관적 락 버전. 동시에 수정하면 늦게 저장하는 쪽이 실패한다 (값은 JPA가 관리, MemberSupport.flush가 409로 바꾼다) */
    @Version
    private Long version;

    private User(String email, String nickname) {
        super(Nickname.validate(nickname), Role.USER, UserStatus.ACTIVE);
        this.email = email;
    }

    /** 신규 가입 (USER, ACTIVE, 프로필 이미지 없음) */
    public static User join(String email, String nickname) {
        return new User(email, nickname);
    }

    /** 닉네임 변경 (닉네임 규칙 검증) */
    public void changeNickname(String nickname) {
        changeProfile(Nickname.validate(nickname), getRole(), getStatus());
    }

    /** 콘텐츠 분야 코드 (#711). 고른 순서이고, 바꿀 수 없는 복사본이다 */
    public List<String> getContentCategories() {
        return List.copyOf(contentCategories);
    }

    /**
     * 콘텐츠 분야 변경 (#711, 개수·중복 검증). 지금과 같으면 바꾸지 않고 false를 돌려준다(목록을 다시 채우지 않아 version이 오르지 않는다).
     * 코드가 분야 목록에 있는지·새로 고를 수 있는지는 부르는 쪽이 먼저 확인한다
     */
    public boolean changeContentCategories(List<String> codes) {
        List<String> validated = ContentCategories.validate(codes);
        if (validated.equals(contentCategories)) {
            return false;
        }
        this.contentCategories = new ArrayList<>(validated);
        return true;
    }

    /** 자기소개 변경 (#711, 길이 검증·앞뒤 공백 제거, 비면 null). 지금과 같으면 바꾸지 않고 false를 돌려준다 */
    public boolean changeBio(String bio) {
        String validated = Bio.validate(bio);
        if (Objects.equals(validated, this.bio)) {
            return false;
        }
        this.bio = validated;
        return true;
    }

    /** 프로필 이미지 변경 (S3 키, null이면 삭제). API는 S3 공통 설정 이후 */
    public void changeProfileImage(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    /** 역할 변경 (승급·초기 계정·관리자 계정 생성) */
    public void changeRole(Role role) {
        changeProfile(getNickname(), role, getStatus());
    }

    /**
     * 탈퇴 (#593). 상태와 탈퇴 시각을 이 메서드에서만 함께 바꿔 어긋나지 않게 한다. 정상 회원만 탈퇴한다
     * (정지 회원 정책은 정지 기능을 만들 때 정한다). 개인정보는 복구를 위해 그대로 두고, 30일 뒤 마스킹한다
     */
    public void withdraw(LocalDateTime now) {
        if (!isActive()) {
            throw new IllegalStateException("정상 회원만 탈퇴할 수 있습니다. status=" + getStatus());
        }
        changeProfile(getNickname(), getRole(), UserStatus.WITHDRAWN);
        this.deletedAt = now;
        this.restoreFailedAt = null;
    }

    /** 복구할 수 있는지: 탈퇴했고, 마스킹 전이고, {@link #restorableUntil()} 전 */
    public boolean canRestore(LocalDateTime now) {
        LocalDateTime until = restorableUntil();
        return until != null && maskedAt == null && now.isBefore(until);
    }

    /**
     * 복구할 수 있는 마지막 시각. 탈퇴 시각 + {@link #RESTORE_PERIOD}이고, 그 안에 지갑 장애로 복구가 실패했으면
     * {@link #RESTORE_GRACE}를 더한다 (#594). 마스킹 배치도 이 시각이 지난 회원만 고른다. 탈퇴하지 않았으면 null
     */
    public LocalDateTime restorableUntil() {
        if (!isWithdrawn() || deletedAt == null) {
            return null;
        }
        LocalDateTime deadline = deletedAt.plus(RESTORE_PERIOD);
        return restoreFailedAt != null && restoreFailedAt.isBefore(deadline) ? deadline.plus(RESTORE_GRACE) : deadline;
    }

    /**
     * 지갑 장애로 복구가 실패했음을 남긴다 (#594). 원래 기한({@link #RESTORE_PERIOD}) 안의 첫 실패만 남긴다.
     * 유예 중에 다시 실패해도 유예를 또 늘리지 않는다
     */
    public void recordRestoreFailure(LocalDateTime now) {
        if (isWithdrawn() && deletedAt != null && restoreFailedAt == null
                && now.isBefore(deletedAt.plus(RESTORE_PERIOD))) {
            this.restoreFailedAt = now;
        }
    }

    /** 복구 (#593): 정상 회원으로 되돌린다. 같은 계정 그대로이고 기록·날짜는 바뀌지 않는다 */
    public void restore(LocalDateTime now) {
        if (!canRestore(now)) {
            throw new IllegalStateException("복구할 수 없는 회원입니다. status=" + getStatus() + ", deletedAt=" + deletedAt);
        }
        changeProfile(getNickname(), getRole(), UserStatus.ACTIVE);
        this.deletedAt = null;
        this.restoreFailedAt = null;
    }

    /**
     * 개인정보 마스킹 (#594, docs/policy.md 삭제 정책). 복구 기한이 지난 탈퇴 회원만 한 번 한다.
     * 이메일은 지운다(null). 빈 값끼리는 유일 제약에 걸리지 않아 다른 회원과 겹치거나 미리 선점될 일이 없고, 로그인에서도 찾아지지 않으며,
     * 같은 이메일로 새로 가입할 수 있다. 닉네임은 다른 앱 복제본(채팅은 not null)에 나가므로 회원 ID를 넣은 값으로 바꾼다.
     * 프로필 사진 연결을 끊고, 콘텐츠 분야·자기소개를 지운다(#711).
     * 닉네임은 규칙 검사를 거치지 않는다({@link #MASKED_NICKNAME_PREFIX}의 {@code #}은 규칙 밖 글자다). 회원 행·ID·상태(탈퇴)는 남긴다
     */
    public void mask(LocalDateTime now) {
        if (!isWithdrawn() || maskedAt != null || canRestore(now)) {
            throw new IllegalStateException("마스킹할 수 없는 회원입니다. status=" + getStatus() + ", maskedAt=" + maskedAt
                    + ", restorableUntil=" + restorableUntil());
        }
        changeProfile(MASKED_NICKNAME_PREFIX + getId(), getRole(), getStatus());
        this.email = null;
        this.profileImageKey = null;
        changeContentCategories(List.of());
        this.bio = null;
        this.maskedAt = now;
    }

    /** 마스킹했는지 */
    public boolean isMasked() {
        return maskedAt != null;
    }

    /**
     * 다른 모듈에 넘길 회원 정보. 버전은 flush 뒤에 불러야 올라간 값이 담긴다 (MemberSupport.flush).
     * 콘텐츠 분야·자기소개도 늘 지금 값을 싣는다(빠뜨리면 받는 쪽은 지운 것으로 본다, #711). 분야를 읽으므로 트랜잭션 안에서 부른다
     */
    public UserDto toDto() {
        return new UserDto(getId(), getNickname(), getRole(), getStatus(), version, getContentCategories(), bio);
    }
}

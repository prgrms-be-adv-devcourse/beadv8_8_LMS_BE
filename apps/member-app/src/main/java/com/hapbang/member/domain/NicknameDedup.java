package com.hapbang.member.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 마이그레이션이 바꾼 중복 닉네임 기록 (#646, V202611040948). 행은 마이그레이션만 만든다.
 * 앱은 아직 알리지 않은 행({@code notifiedAt}이 비어 있음)의 회원 UserModifiedEvent를 보내고 알림 표시를 남긴다
 */
@Entity
@Table(name = "nickname_dedup", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NicknameDedup {

    /** 닉네임이 바뀐 회원 ID */
    @Id
    private Long userId;

    @Column(nullable = false)
    private String oldNickname;

    @Column(nullable = false)
    private String newNickname;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 다른 앱에 알린 시각. 비어 있으면 아직 안 알렸다 */
    private LocalDateTime notifiedAt;

    public void markNotified(LocalDateTime now) {
        this.notifiedAt = now;
    }
}

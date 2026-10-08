package com.hapbang.chat.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 이벤트를 받아 채팅 모듈에 복제한 회원 정보. ID는 회원 ID를 그대로 쓴다.
 */
@Entity
@Table(name = "chat_user", schema = "chat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatUser {

    public static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

    @Id
    private Long id;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    private ChatUser(Long id, String nickname, UserRole role, LocalDateTime now) {
        this.id = id;
        this.nickname = nickname;
        this.role = role;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ChatUser create(Long userId, String nickname, UserRole role, LocalDateTime now) {
        if (userId == null || nickname == null || nickname.isBlank() || role == null) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
        return new ChatUser(userId, nickname, role, now);
    }

    public void changeProfile(String nickname, UserRole role, LocalDateTime now) {
        if (nickname == null || nickname.isBlank() || role == null) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }
        this.nickname = nickname;
        this.role = role;
        this.updatedAt = now;
    }

    /**
     * 탈퇴한 회원은 채팅 작성자를 '탈퇴한 회원'으로 표시한다. 메시지는 분쟁 확인용으로 남긴다.
     */
    public void withdraw(LocalDateTime now) {
        this.nickname = WITHDRAWN_NICKNAME;
        this.updatedAt = now;
        this.deletedAt = now;
    }

    public boolean isWithdrawn() {
        return deletedAt != null;
    }

    public boolean canChat() {
        return !isWithdrawn() && role != UserRole.USER;
    }

    public boolean isAdmin() {
        return !isWithdrawn() && role == UserRole.ADMIN;
    }
}

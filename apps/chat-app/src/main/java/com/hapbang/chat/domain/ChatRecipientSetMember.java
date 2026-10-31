package com.hapbang.chat.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 불변 수신자 집합의 항목. 집합을 만들 때 활성 참여 행에서 한꺼번에 복사하고({@code INSERT ... SELECT}), 고치지 않는다.
 *
 * @see ChatRecipientSet
 */
@Entity
@Table(name = "chat_recipient_set_member", schema = "chats")
@IdClass(ChatRecipientSetMember.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRecipientSetMember {

    @Id
    @Column(name = "recipient_set_id", nullable = false)
    private Long recipientSetId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 집합을 만들 때 이 회원의 참여 세대 */
    @Column(nullable = false)
    private int participationVersion;

    /** 복합 키 (집합, 회원) */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        private Long recipientSetId;
        private Long userId;
    }
}

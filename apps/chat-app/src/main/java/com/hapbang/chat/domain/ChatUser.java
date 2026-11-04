package com.hapbang.chat.domain;

import org.springframework.util.Assert;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 회원 이벤트를 받아 채팅 모듈에 복제한 회원 정보. ID는 원본 회원 ID를 그대로 쓴다({@link ReplicaUser}).
 */
@Entity
@Table(name = "chat_user", schema = "chats")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatUser extends ReplicaUser {

    public static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";

    /** 채팅 회원 복제본이 없는 회원의 표시 닉네임. 회원 ID는 사용자에게 드러내지 않으므로 대신 쓴다 */
    public static final String UNKNOWN_NICKNAME = "알 수 없는 회원";

    private ChatUser(UserDto user) {
        super(user);
    }

    public static ChatUser create(UserDto user) {
        validate(user);
        return new ChatUser(user);
    }

    @Override
    public void syncFrom(UserDto user) {
        validate(user);
        super.syncFrom(user);
    }

    /**
     * 정상 상태이면서 인플루언서 이상이거나 관리자면 채팅할 수 있다.
     */
    public boolean canChat() {
        return isActive() && (isInfluencerOrAbove() || getRole() == Role.ADMIN);
    }

    /**
     * 모집용 단체방의 호스트를 계속할 수 있는지: 탈퇴하지 않았고 출연 모집을 올릴 수 있는 역할(인플루언서·인증된 인플루언서)이다.
     * 정지는 따로 다루므로(일시 중지) 여기서는 자격 상실로 보지 않는다. 인증된 인플루언서가 인플루언서가 되어도 자격은 유지된다.
     */
    public boolean canHostGroup() {
        return !isWithdrawn() && isInfluencerOrAbove();
    }

    public boolean isAdmin() {
        return isActive() && getRole() == Role.ADMIN;
    }

    /**
     * 탈퇴한 회원은 채팅 작성자를 '탈퇴한 회원'으로 표시한다. 메시지는 분쟁 확인용으로 남긴다.
     */
    public String getDisplayNickname() {
        return isWithdrawn() ? WITHDRAWN_NICKNAME : getNickname();
    }

    /** 복제할 수 있는 회원 정보인지. UseCase가 도메인 호출 전에 같은 기준으로 검사한다 */
    public static boolean isReplicable(UserDto user) {
        return user != null && user.id() != null && user.nickname() != null && !user.nickname().isBlank()
                && user.role() != null && user.status() != null;
    }

    private static void validate(UserDto user) {
        Assert.state(isReplicable(user), "복제할 수 없는 회원 정보입니다.");
    }
}

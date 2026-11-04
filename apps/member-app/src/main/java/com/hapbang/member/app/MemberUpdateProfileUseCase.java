package com.hapbang.member.app;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.hapbang.member.domain.Nickname;
import com.hapbang.member.domain.User;
import com.hapbang.shared.member.event.UserModifiedEvent;

import lombok.RequiredArgsConstructor;

/** 내 정보 수정. 닉네임이 바뀌면 UserModifiedEvent 발행. 다른 회원이 쓰는 닉네임이면 DUPLICATE_NICKNAME (#646) */
@Service
@RequiredArgsConstructor
public class MemberUpdateProfileUseCase {

    private final MemberSupport memberSupport;
    private final ApplicationEventPublisher eventPublisher;

    /** nickname이 null이면 바꾸지 않는다 (PATCH) */
    public User update(Long userId, String nickname) {
        User user = memberSupport.findUser(userId);
        if (nickname != null) {
            String validated = Nickname.validate(nickname);
            // 바꾸기 전에 확인한다. 내 닉네임 그대로거나 대소문자만 바꾸면 통과
            memberSupport.checkNicknameAvailable(validated, userId);
            user.changeNickname(validated);
            // 역할 변경과 겹치거나 같은 닉네임이 먼저 저장되면 409 (이벤트 발행 전)
            memberSupport.flush();
            eventPublisher.publishEvent(new UserModifiedEvent(user.toDto()));
        }
        return user;
    }
}

package com.hapbang.chat.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.domain.UserRole;
import com.hapbang.chat.out.ChatUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 회원 정보를 채팅 모듈에 복제한다. 회원 이벤트(shared/member/event)가 생기면 리스너에서 호출한다.
 */
// TODO(event): 회원 모듈이 shared/member/event에 이벤트 DTO를 만들면 chat에 리스너(@ApplicationModuleListener)를 추가해
//  sync()·withdraw()를 호출한다. 필요한 이벤트: 가입, 역할 변경(채널 인증·승급), 닉네임 변경, 탈퇴(30일 후 개인정보 파기).
//  지금은 이벤트가 없어 테스트에서 sync()를 직접 호출한다(ChatUserSeeder).
@Service
@Transactional
@RequiredArgsConstructor
public class ChatUserService {

    private final ChatUserRepository chatUserRepository;

    public void sync(Long userId, String nickname, UserRole role) {
        LocalDateTime now = LocalDateTime.now();
        chatUserRepository.findById(userId).ifPresentOrElse(
                chatUser -> chatUser.changeProfile(nickname, role, now),
                () -> chatUserRepository.save(ChatUser.create(userId, nickname, role, now))
        );
    }

    public void withdraw(Long userId) {
        chatUserRepository.findById(userId)
                .ifPresent(chatUser -> chatUser.withdraw(LocalDateTime.now()));
    }
}

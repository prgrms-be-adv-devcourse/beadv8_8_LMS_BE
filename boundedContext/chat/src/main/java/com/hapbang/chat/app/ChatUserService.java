package com.hapbang.chat.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 정보를 채팅 모듈에 복제한다. 처음 받은 회원은 만들고, 이미 있으면 원본 변경 내용을 반영한다.
 * 회원 이벤트 리스너({@code ChatUserEventListener})가 호출한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ChatUserService {

    private final ChatUserRepository chatUserRepository;

    public void sync(UserDto user) {
        chatUserRepository.findById(user.id()).ifPresentOrElse(
                chatUser -> chatUser.syncFrom(user),
                () -> chatUserRepository.save(ChatUser.create(user))
        );
    }
}

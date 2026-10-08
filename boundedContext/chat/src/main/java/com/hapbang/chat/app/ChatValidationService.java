package com.hapbang.chat.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.chat.domain.ChatErrorCode;
import com.hapbang.chat.domain.ChatException;
import com.hapbang.chat.domain.ChatUser;
import com.hapbang.chat.out.ChatUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 회원 권한 검증. 복제한 {@link ChatUser}의 역할·탈퇴 여부로 판단한다.
 * 쓰기 서비스에서 호출하면 그 트랜잭션에 참여한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ChatValidationService {

    private final ChatUserRepository chatUserRepository;

    /**
     * 채팅할 수 있는 회원(인플루언서 이상·관리자, 탈퇴하지 않음)인지 확인하고 반환한다.
     */
    public ChatUser getChattableUser(Long userId) {
        ChatUser chatUser = getChatUser(userId);
        if (!chatUser.canChat()) {
            throw new ChatException(ChatErrorCode.CHAT_NOT_ALLOWED);
        }
        return chatUser;
    }

    public void validateCanChat(Long userId) {
        getChattableUser(userId);
    }

    public void validateAdmin(Long userId) {
        if (!getChatUser(userId).isAdmin()) {
            throw new ChatException(ChatErrorCode.ADMIN_ONLY);
        }
    }

    private ChatUser getChatUser(Long userId) {
        return chatUserRepository.findById(userId)
                .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_USER_NOT_FOUND));
    }
}

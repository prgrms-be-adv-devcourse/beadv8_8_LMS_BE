package com.hapbang.chat.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.chat.domain.ChatUser;

public interface ChatUserRepository extends JpaRepository<ChatUser, Long> {
}

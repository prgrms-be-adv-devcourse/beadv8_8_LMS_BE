package com.hapbang.chat.app;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatRoom;

/**
 * 채팅방 테스트 검증용 조회. 운영 코드가 쓰지 않는 조회라 port({@link ChatRoomRepository})에 두지 않고 테스트 소스에 둔다.
 * 테스트 실행 때는 같은 패키지라 Spring Data가 함께 등록한다. 기본 메서드는 이름과 시그니처가 같아 기본 구현이 처리한다.
 */
public interface ChatRoomTestRepository extends Repository<ChatRoom, Long> {

    List<ChatRoom> findAll();
    long count();
}

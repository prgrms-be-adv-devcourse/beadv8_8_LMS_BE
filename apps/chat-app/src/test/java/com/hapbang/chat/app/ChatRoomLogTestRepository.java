package com.hapbang.chat.app;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.hapbang.chat.domain.ChatRoomLog;

/**
 * 방 이벤트 기록 테스트 검증용 조회. 운영 코드가 쓰지 않는 조회라 port({@link ChatRoomLogRepository})에 두지 않고 테스트 소스에 둔다. 테스트는
 * {@code ModuleTestConfiguration}의 {@code @AutoConfigurationPackage("com.hapbang")}로 {@code com.hapbang} 아래 테스트 소스도
 * 스캔하므로 Spring Data가 함께 등록한다. 기본 메서드는 이름과 시그니처가 같아 기본 구현이 처리한다.
 */
public interface ChatRoomLogTestRepository extends Repository<ChatRoomLog, Long> {

    long count();

    List<ChatRoomLog> findByChatRoom_IdOrderByIdAsc(Long chatRoomId);
}

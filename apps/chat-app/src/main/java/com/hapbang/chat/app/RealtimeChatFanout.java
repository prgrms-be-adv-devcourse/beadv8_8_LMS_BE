package com.hapbang.chat.app;

import com.hapbang.chat.app.dto.ControlFanout;
import com.hapbang.chat.app.dto.MessageFanout;

/**
 * 커밋된 전달·제어 작업을 실시간 경로(방 채널·서버 채널)로 내보낸다. 발행 성공은 사용자 수신이 아니다.
 * DB 트랜잭션 밖에서 부른다. 계약: {@code apps/chat-app/docs/contracts/realtime-fanout.md}
 */
public interface RealtimeChatFanout {

    /**
     * 메시지 전달 작업을 발행한다. 늘 구독하는 서버 채널에 알림 재료를 먼저 내고, 구독자가 있으면 방 채널에 메시지를 낸다.
     *
     * @return 서버 채널 발행 명령이 성공한 순간의 구독자 수(0이면 자기 구독 단절이며 방 채널은 발행하지 않았다). 방 채널의 구독자
     * 수는 성공 판단에 쓰지 않는다. 실패하면 예외
     */
    long publishMessage(MessageFanout fanout);

    /**
     * 연결 제어 작업을 발행한다.
     *
     * @return 발행 명령이 성공한 순간의 구독자 수. 실패하면 예외
     */
    long publishControl(ControlFanout fanout);
}

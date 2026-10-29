# 계약 (a): 연결 제어 작업 기록 (#417 → #418)

회원 반영 결과가 채팅 불가일 때 연결 재검사 작업을 남기는 계약이다. 설계는 [설계 문서](../final-design.md) 5.3, 테이블은 [테이블 계약](tables-and-migrations.md)이다.

## 소유

- 인터페이스, 제어 outbox·발행 체크포인트 테이블과 기록 구현은 **#418이 소유**한다. [공통 골격](parallel-work.md#공통-골격-동결)으로 #417·#418·#419에 같은 내용으로 넣고 고치지 않는다.
- #417은 인터페이스를 호출만 한다. 적재·발행·ACK·회수·재구성은 #418, 제어 채널 수신과 로컬 연결 종료는 #419다.

## 인터페이스

```java
package com.hapbang.chat.app;

public interface ChatConnectionControl {

    /**
     * 회원의 로컬 연결 재검사 작업(RECHECK_USER)을 기록한다. 호출한 쪽의 트랜잭션 안에서만 쓴다(MANDATORY).
     * 같은 (회원, 원본 버전, 종류)는 한 번만 기록하고, 이미 있으면 아무것도 하지 않는다.
     *
     * @param memberVersion 반영한 회원 원본 버전. 버전 없는 회원 정보면 null
     */
    void requestRecheck(Long userId, Long memberVersion, ChatControlReason reason);
}
```

- `ChatControlReason`: `MEMBER_MODIFIED`(Kafka 회원 변경 이벤트), `MEMBER_BACKFILL`(5.4 백필).
- 구현 `out/JdbcChatConnectionControl`은 `@Transactional(propagation = MANDATORY)`다. 트랜잭션 없이 부르면 예외다.
- 기록은 `chat_control_outbox` 한 행(`PENDING`, 새 `controlId`)과 `chat_publish_checkpoint` 한 행(그룹 `chat-control-v1`, `PENDING`)을 같은 트랜잭션에 넣는다. 중복은 `ON CONFLICT DO NOTHING`으로 흡수해 호출한 트랜잭션을 rollback-only로 만들지 않는다.

## 호출 규칙 (#417)

- **기존 복제본을 갱신**(Kafka 변경 이벤트·백필 갱신)한 같은 트랜잭션에서, **이번에 반영한 최신 상태가 채팅 불가**이면 호출한다. 기존 `lostChat`(가능 → 불가 전이)만으로 판단하지 않는다.
- 복제본을 새로 만든 경우(가입 이벤트·백필 생성)는 호출하지 않는다. 복제본이 없던 회원은 STOMP 연결 검사(`canChat`)를 통과하지 못해 닫을 연결이 없다.
- 옛 버전이라 반영을 건너뛴 경우에는 호출하지 않는다. 이미 같은 버전의 작업이 있으면 구현이 중복을 흡수한다. 버전 없는(null) 정보의 반복 변경처럼 중복으로 흡수된 경우는 #419의 주기적 재검사가 회복한다.
- 회원 반영이 롤백되면 제어 작업도 남지 않는다. Kafka offset은 이 커밋 뒤에 처리한다.
- `ChatWebSocketSessionRegistry.closeByUserId`를 회원 이벤트 리스너에서 직접 부르지 않는다. 연결 종료는 제어 작업 → #418 → #419 경로로 한다.

## 이후 처리 (#418·#419)

- #418 relay가 `chat_control_outbox`를 `<namespace>:chat:control:stream:v1`에 `XADD`하고, 그룹 `chat-control-v1` consumer가 `RealtimeChatFanout.publishControl`을 부른 뒤 체크포인트 `PUBLISHED` → `XACK`한다.
- #419 구독자는 자기 chat DB의 최신 회원 상태를 확인해 지금도 채팅 불가일 때만 그 회원의 로컬 연결을 `CHAT_NOT_ALLOWED`로 닫는다. 옛 명령으로 자격이 복구된 회원을 닫지 않는다.

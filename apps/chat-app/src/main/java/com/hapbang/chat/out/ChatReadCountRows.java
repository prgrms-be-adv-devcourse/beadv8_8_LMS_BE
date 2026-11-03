package com.hapbang.chat.out;

/**
 * 메시지별 안 읽은 사람 수 native 집계의 interface projection. 컬럼 별칭(snake_case)을 속성 이름으로 읽는다.
 * 계약: {@code apps/chat-app/docs/contracts/unread-count.md}
 */
public final class ChatReadCountRows {

    private ChatReadCountRows() {
    }

    /** 메시지 ID로 센 한 행 */
    public interface MessageUnreadRow {

        long getMessageId();

        long getUnreadMemberCount();

        /** 센 문장의 DB 시각(epoch 마이크로초) */
        long getReadCountVersion();
    }

    /**
     * 방의 최근 순번 구간을 센 한 행. 방이 있으면 일반 메시지가 없어도 구간 행 하나({@code roomSequence} null)가 나온다.
     */
    public interface RecentUnreadRow {

        long getFromSequence();

        long getToSequence();

        /** 같은 스냅샷의 방 현재 수신자 집합 버전. 집합이 없으면 0 */
        int getRecipientSetVersion();

        /** 구간 안의 일반 메시지 순번. 구간에 일반 메시지가 없으면 null */
        Long getRoomSequence();

        long getUnreadMemberCount();

        /** 센 문장의 DB 시각(epoch 마이크로초) */
        long getReadCountVersion();
    }
}

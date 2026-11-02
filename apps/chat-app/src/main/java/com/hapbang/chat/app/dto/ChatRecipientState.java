package com.hapbang.chat.app.dto;

/**
 * 실시간 전달 직전에 다시 읽은 수신 후보의 최신 상태.
 *
 * @param canChat              채팅 회원 자격(정상 상태의 인플루언서 이상·관리자)
 * @param activeParticipant    지금 그 방에 참여 중인지(나가지·삭제되지 않음). 참여 행이 없으면 false
 * @param participationVersion 현재 참여 세대. 참여 행이 없으면 0
 * @param visibleFromSequence  현재 참여에서 볼 수 있는 첫 순번. 참여 행이 없으면 {@link Long#MAX_VALUE}
 */
public record ChatRecipientState(
        Long userId,
        boolean canChat,
        boolean activeParticipant,
        int participationVersion,
        long visibleFromSequence
) {

    /**
     * 메시지 저장 당시의 참여 세대와 순번으로 지금 받을 수 있는지. 자격·현재 참여·세대 일치·가시 범위를 모두 만족해야 한다.
     */
    public boolean canReceive(int expectedParticipationVersion, long roomSequence) {
        return canChat && activeParticipant && participationVersion == expectedParticipationVersion
                && roomSequence >= visibleFromSequence;
    }
}

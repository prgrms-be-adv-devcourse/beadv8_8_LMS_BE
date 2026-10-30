package com.hapbang.chat.app.dto;

/**
 * 수신 확인 결과.
 *
 * @param participationVersion 반영한 참여 세대
 * @param lastReceivedSequence 반영 뒤 서버의 누적 수신 확인 순번. 다른 기기가 더 높게 올렸으면 요청 값보다 클 수 있다.
 *                             "적어도 한 기기가 확인한 위치"이므로 기기의 확인 위치로 복사하지 않는다
 */
public record ChatReceiptResponse(Long chatRoomId, int participationVersion, long lastReceivedSequence) {
}

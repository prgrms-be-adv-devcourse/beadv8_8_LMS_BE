package com.hapbang.chat.app.dto;

import java.util.List;

/**
 * @param nextCursor 다음 페이지 요청에 쓸 커서(체크포인트 ID). 더 없으면 null
 */
public record ChatIsolatedJobPage(List<ChatIsolatedJob> items, Long nextCursor) {
}

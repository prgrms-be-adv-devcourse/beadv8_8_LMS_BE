package com.hapbang.auxiliary.chatbot.support;

import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 가짜 원본의 키셋 페이지. 규칙은 내부 API들과 같다(ID 오름차순, 다음 페이지가 있으면 이 페이지의 마지막 ID) */
final class FakeKeysetPages {

    private FakeKeysetPages() {
    }

    static <T, P> P page(Collection<T> all, Function<T, Long> id, Long afterId, int size, BiFunction<List<T>, Long, P> pageOf) {
        long start = afterId == null ? 0 : afterId;
        List<T> after = all.stream().filter(item -> id.apply(item) > start)
                .sorted((a, b) -> Long.compare(id.apply(a), id.apply(b))).toList();
        boolean hasNext = after.size() > size;
        List<T> items = hasNext ? after.subList(0, size) : after;
        return pageOf.apply(items, hasNext ? id.apply(items.getLast()) : null);
    }
}

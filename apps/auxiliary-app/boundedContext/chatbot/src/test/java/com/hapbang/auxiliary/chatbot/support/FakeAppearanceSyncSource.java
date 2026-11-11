package com.hapbang.auxiliary.chatbot.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.hapbang.auxiliary.chatbot.out.AppearanceSyncSource;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

/**
 * 출연 원본을 메모리로 흉내 낸다. 키셋 규칙은 출연 내부 API와 같다(ID 오름차순, 다음 페이지가 있으면 마지막 ID).
 * 테스트 클래스에서 {@code @TestBean(methodName = FakeAppearanceSyncSource.FACTORY)}로 바꿔 끼운다. 인스턴스가 하나라 테스트마다
 * {@link #reset()}한다.
 */
public final class FakeAppearanceSyncSource implements AppearanceSyncSource {

    public static final String FACTORY = "com.hapbang.auxiliary.chatbot.support.FakeAppearanceSyncSource#source";

    private static final FakeAppearanceSyncSource INSTANCE = new FakeAppearanceSyncSource();

    private final Map<Long, AppearanceSyncDto> appearances = new TreeMap<>();
    private final Map<Long, AppearanceReviewSyncDto> reviews = new TreeMap<>();
    private volatile int calls;
    private volatile int failAtCall = -1;
    private volatile RuntimeException failure;
    private volatile Runnable afterFirstPage;
    private volatile Function<AppearanceSyncPageDto, AppearanceSyncPageDto> tamper;
    private volatile CountDownLatch gate;

    public static FakeAppearanceSyncSource source() {
        return INSTANCE;
    }

    public synchronized void reset() {
        appearances.clear();
        reviews.clear();
        calls = 0;
        failAtCall = -1;
        failure = null;
        afterFirstPage = null;
        tamper = null;
        gate = null;
    }

    public synchronized void put(AppearanceSyncDto... items) {
        for (AppearanceSyncDto item : items) {
            appearances.put(item.id(), item);
        }
    }

    public synchronized void remove(long id) {
        appearances.remove(id);
    }

    public synchronized void putReviews(AppearanceReviewSyncDto... items) {
        for (AppearanceReviewSyncDto item : items) {
            reviews.put(item.reviewId(), item);
        }
    }

    /** n번째 호출(1부터)에서 이 예외를 던진다 */
    public void failAt(int call, RuntimeException failure) {
        this.failAtCall = call;
        this.failure = failure;
    }

    /** 첫 페이지를 준 뒤(다음 페이지를 읽기 전) 실행한다. 스캔 중 이벤트를 흉내 낸다 */
    public void afterFirstPage(Runnable action) {
        this.afterFirstPage = action;
    }

    /** 출연 응답을 바꾼다(계약 위반 흉내) */
    public void tamper(Function<AppearanceSyncPageDto, AppearanceSyncPageDto> tamper) {
        this.tamper = tamper;
    }

    /** 열릴 때까지 첫 호출을 붙잡는다(실행 중 흉내) */
    public CountDownLatch hold() {
        CountDownLatch latch = new CountDownLatch(1);
        this.gate = latch;
        return latch;
    }

    public int calls() {
        return calls;
    }

    @Override
    public AppearanceSyncPageDto appearances(Long afterId, int size) {
        beforeCall(afterId);
        AppearanceSyncPageDto page;
        synchronized (this) {
            page = page(new ArrayList<>(appearances.values()), AppearanceSyncDto::id, afterId, size,
                    AppearanceSyncPageDto::new);
        }
        return tamper == null ? page : tamper.apply(page);
    }

    @Override
    public AppearanceReviewSyncPageDto reviews(Long afterId, int size) {
        beforeCall(afterId);
        synchronized (this) {
            return page(new ArrayList<>(reviews.values()), AppearanceReviewSyncDto::reviewId, afterId, size,
                    AppearanceReviewSyncPageDto::new);
        }
    }

    private void beforeCall(Long afterId) {
        int call = ++calls;
        CountDownLatch latch = gate;
        if (latch != null) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (afterId != null && afterFirstPage != null) {
            Runnable action = afterFirstPage;
            afterFirstPage = null;
            action.run();
        }
        if (call == failAtCall) {
            throw failure;
        }
    }

    private static <T, P> P page(List<T> all, Function<T, Long> id, Long afterId, int size,
            java.util.function.BiFunction<List<T>, Long, P> pageOf) {
        long start = afterId == null ? 0 : afterId;
        List<T> after = all.stream().filter(item -> id.apply(item) > start).toList();
        boolean hasNext = after.size() > size;
        List<T> items = hasNext ? after.subList(0, size) : after;
        return pageOf.apply(items, hasNext ? id.apply(items.getLast()) : null);
    }
}

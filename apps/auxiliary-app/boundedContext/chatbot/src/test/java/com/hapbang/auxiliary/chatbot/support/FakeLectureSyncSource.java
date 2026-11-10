package com.hapbang.auxiliary.chatbot.support;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import com.hapbang.auxiliary.chatbot.out.LectureSyncSource;
import com.hapbang.shared.lecture.dto.CartItemSyncDto;
import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

/**
 * 강의 원본 4종을 메모리로 흉내 낸다 (#717). {@code @TestBean(methodName = FakeLectureSyncSource.FACTORY)}로 바꿔 끼우고 테스트마다
 * {@link #reset()}한다.
 */
public final class FakeLectureSyncSource implements LectureSyncSource {

    public static final String FACTORY = "com.hapbang.auxiliary.chatbot.support.FakeLectureSyncSource#source";

    private static final FakeLectureSyncSource INSTANCE = new FakeLectureSyncSource();

    private final Map<Long, LectureSyncDto> lectures = new TreeMap<>();
    private final Map<Long, LecturePurchaseSyncDto> purchases = new TreeMap<>();
    private final Map<Long, CartItemSyncDto> cartItems = new TreeMap<>();
    private final Map<Long, LectureReviewSyncDto> reviews = new TreeMap<>();

    public static FakeLectureSyncSource source() {
        return INSTANCE;
    }

    public synchronized void reset() {
        lectures.clear();
        purchases.clear();
        cartItems.clear();
        reviews.clear();
    }

    public synchronized void putLectures(LectureSyncDto... items) {
        for (LectureSyncDto item : items) {
            lectures.put(item.id(), item);
        }
    }

    public synchronized void putPurchases(LecturePurchaseSyncDto... items) {
        for (LecturePurchaseSyncDto item : items) {
            purchases.put(item.orderItemId(), item);
        }
    }

    public synchronized void putCartItems(CartItemSyncDto... items) {
        for (CartItemSyncDto item : items) {
            cartItems.put(item.id(), item);
        }
    }

    public synchronized void putReviews(LectureReviewSyncDto... items) {
        for (LectureReviewSyncDto item : items) {
            reviews.put(item.id(), item);
        }
    }

    public synchronized void removeLecture(long id) {
        lectures.remove(id);
    }

    public synchronized void removePurchase(long orderItemId) {
        purchases.remove(orderItemId);
    }

    @Override
    public synchronized LectureSyncPageDto lectures(Long afterId, int size) {
        return FakeKeysetPages.page(new ArrayList<>(lectures.values()), LectureSyncDto::id, afterId, size,
                LectureSyncPageDto::new);
    }

    @Override
    public synchronized LecturePurchaseSyncPageDto purchases(Long afterId, int size) {
        return FakeKeysetPages.page(new ArrayList<>(purchases.values()), LecturePurchaseSyncDto::orderItemId, afterId, size,
                LecturePurchaseSyncPageDto::new);
    }

    @Override
    public synchronized CartItemSyncPageDto cartItems(Long afterId, int size) {
        return FakeKeysetPages.page(new ArrayList<>(cartItems.values()), CartItemSyncDto::id, afterId, size,
                CartItemSyncPageDto::new);
    }

    @Override
    public synchronized LectureReviewSyncPageDto reviews(Long afterId, int size) {
        return FakeKeysetPages.page(new ArrayList<>(reviews.values()), LectureReviewSyncDto::id, afterId, size,
                LectureReviewSyncPageDto::new);
    }
}

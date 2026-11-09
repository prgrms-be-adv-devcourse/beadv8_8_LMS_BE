package com.hapbang.auxiliary.global.scheduling;

/** 락 테스트용 작업. 번호 규칙(이슈 번호 × 1000 + 순번)을 흉내 낸다 */
enum TestJob implements LockedJob {
    A(715_901L, "test-a"),
    B(715_902L, "test-b"),
    C(715_903L, "test-c"),
    D(715_904L, "test-d"),
    E(715_905L, "test-e"),
    F(715_906L, "test-f");

    private final long lockKey;
    private final String tag;

    TestJob(long lockKey, String tag) {
        this.lockKey = lockKey;
        this.tag = tag;
    }

    @Override
    public long lockKey() {
        return lockKey;
    }

    @Override
    public String tag() {
        return tag;
    }
}

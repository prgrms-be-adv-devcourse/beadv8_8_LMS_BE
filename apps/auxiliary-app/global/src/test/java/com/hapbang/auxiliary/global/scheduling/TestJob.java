package com.hapbang.auxiliary.global.scheduling;

/** 테스트용 작업. 실제 번호 규칙(이슈 번호 × 1000 + 순번)과 겹치지 않게 한 자리 번호를 쓴다 */
enum TestJob implements JobLockKey {

    FIRST(1L, "test-first"),
    SECOND(2L, "test-second");

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

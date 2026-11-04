package com.hapbang.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Mock 은행 가상 송금 기록(#288)의 같은 요청 판정·상태 전이를 DB 없이 검증한다.
class MockPayoutTransferTest {

    private static final Instant T0 = Instant.parse("2026-10-16T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    @DisplayName("같은 요청은 환전 ID·금액·은행·계좌 해시·예금주가 모두 같아야 한다")
    void sameRequest() {
        MockPayoutTransfer transfer = MockPayoutTransfer.received("id", 1L, 18_000L, "004", HASH, "홍길동", T0);

        assertThat(transfer.sameRequest(1L, 18_000L, "004", HASH, "홍길동")).isTrue();
        assertThat(transfer.sameRequest(2L, 18_000L, "004", HASH, "홍길동")).isFalse();
        assertThat(transfer.sameRequest(1L, 18_001L, "004", HASH, "홍길동")).isFalse();
        assertThat(transfer.sameRequest(1L, 18_000L, "088", HASH, "홍길동")).isFalse();
        assertThat(transfer.sameRequest(1L, 18_000L, "004", "b".repeat(64), "홍길동")).isFalse();
        assertThat(transfer.sameRequest(1L, 18_000L, "004", HASH, "김철수")).isFalse();
    }

    @Test
    @DisplayName("PROCESSING에서만 결과를 한 번 정한다(성공·실패 뒤에는 바꿀 수 없다)")
    void completeOnce() {
        MockPayoutTransfer succeeded = MockPayoutTransfer.received("a", 1L, 1_000L, "004", HASH, "홍길동", T0);
        succeeded.succeed("MOCKTX-1", T0);
        MockPayoutTransfer failed = MockPayoutTransfer.received("b", 1L, 1_000L, "004", HASH, "홍길동", T0);
        failed.fail("MOCK_TRANSFER_REJECTED", T0);

        assertThat(succeeded.getStatus()).isEqualTo(MockPayoutTransferStatus.SUCCEEDED);
        assertThat(failed.getStatus()).isEqualTo(MockPayoutTransferStatus.FAILED);
        assertThatThrownBy(() -> succeeded.fail("X", T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.succeed("MOCKTX-2", T0)).isInstanceOf(IllegalStateException.class);
        assertThat(succeeded.getTransactionId()).isEqualTo("MOCKTX-1");
    }
}

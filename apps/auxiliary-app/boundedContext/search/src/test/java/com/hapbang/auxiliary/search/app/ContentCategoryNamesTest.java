package com.hapbang.auxiliary.search.app;

import static com.hapbang.auxiliary.search.support.FakeMemberStatsSource.category;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.search.support.FakeMemberStatsSource;

/** 콘텐츠 분야 이름 목록: pod마다 10분이 지나면 다시 읽고, 실패하면 1분 동안 묻지 않는다(#771) */
class ContentCategoryNamesTest {

    private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");

    private final FakeMemberStatsSource source = new FakeMemberStatsSource().categories(category("GAME", "게임"));

    private final MutableClock clock = new MutableClock(START);

    private final ContentCategoryNames names = new ContentCategoryNames(source, clock);

    @Test
    @DisplayName("처음 쓸 때 읽고, 10분 안에는 다시 묻지 않는다. 10분이 지나면 다시 읽어 바뀐 이름을 쓴다")
    void refreshesAfterInterval() {
        assertThat(names.nameOf("GAME")).isEqualTo("게임");
        clock.advance(Duration.ofMinutes(9));
        source.categories(category("GAME", "게임 방송"));
        assertThat(names.nameOf("GAME")).isEqualTo("게임");
        assertThat(source.categoryCalls()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        assertThat(names.nameOf("GAME")).isEqualTo("게임 방송");
        assertThat(names.nameOf("UNKNOWN")).isNull();
    }

    @Test
    @DisplayName("읽지 못하면 가진 목록을 그대로 쓰고, 1분 동안 다시 묻지 않는다")
    void keepsNamesOnFailure() {
        names.nameOf("GAME");
        clock.advance(Duration.ofMinutes(10));
        source.failCategories();

        assertThat(names.nameOf("GAME")).isEqualTo("게임");
        assertThat(names.nameOf("GAME")).isEqualTo("게임");
        assertThat(source.categoryCalls()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(1));
        names.nameOf("GAME");
        assertThat(source.categoryCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("동기화가 부르는 refresh는 실패를 그대로 던진다")
    void refreshThrows() {
        source.failCategories();

        assertThatThrownBy(names::refresh).isInstanceOf(IllegalStateException.class);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

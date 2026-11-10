package com.hapbang.auxiliary.search.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.hapbang.auxiliary.search.config.ConditionalOnSearchEnabled;
import com.hapbang.auxiliary.search.out.MemberStatsSource;
import com.hapbang.shared.member.dto.ContentCategoryDto;

import lombok.extern.slf4j.Slf4j;

/**
 * 콘텐츠 분야 코드 → 이름(#771). 회원 이벤트에는 코드만 실리므로 문서에 이름을 붙일 때 쓴다. 원본은 회원 앱의 분야 목록 내부 API(#769)다.
 * <ul>
 *     <li>pod마다 메모리에 둔다. 회원 이벤트는 어느 pod든 받을 수 있어 통계 동기화(한 pod만)가 채우는 방식으로는 다른 pod가 비어 있다.
 *     그래서 쓸 때 10분이 지났으면 그 pod가 직접 다시 읽는다.</li>
 *     <li>읽지 못하면 가진 목록을 그대로 쓰고 1분 동안 다시 묻지 않는다(회원 앱 장애가 이벤트 소비마다 시간 제한만큼 늦추지 않게).
 *     모르는 코드는 이름 없이 색인하고, 다음 통계 동기화가 문서의 이름을 채운다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnSearchEnabled
public class ContentCategoryNames {

    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(10);
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final MemberStatsSource source;
    private final Clock clock;
    private volatile Map<String, String> names = Map.of();
    /** 이 시각 뒤에 쓰면 다시 읽는다 */
    private volatile Instant refreshAt = Instant.MIN;

    @Autowired
    public ContentCategoryNames(MemberStatsSource source) {
        this(source, Clock.systemDefaultZone());
    }

    ContentCategoryNames(MemberStatsSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    /** 코드의 이름. 모르면 null. 목록이 오래됐으면 먼저 다시 읽어 본다 */
    public String nameOf(String code) {
        if (!clock.instant().isBefore(refreshAt)) {
            tryRefresh();
        }
        return names.get(code);
    }

    /**
     * 회원 앱에서 목록을 다시 읽는다(통계 동기화가 부른다).
     *
     * @return 새 목록
     * @throws RuntimeException 읽지 못한 경우({@code MemberApiCallException})
     */
    public synchronized Map<String, String> refresh() {
        try {
            names = source.contentCategories().items().stream()
                    .collect(Collectors.toUnmodifiableMap(ContentCategoryDto::code, ContentCategoryDto::name,
                            (first, second) -> first));
            refreshAt = clock.instant().plus(REFRESH_INTERVAL);
            return names;
        } catch (RuntimeException e) {
            refreshAt = clock.instant().plus(RETRY_AFTER_FAILURE);
            throw e;
        }
    }

    private void tryRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            log.warn("콘텐츠 분야 목록을 읽지 못해 가진 목록으로 색인한다 known={}", names.size(), e);
        }
    }
}

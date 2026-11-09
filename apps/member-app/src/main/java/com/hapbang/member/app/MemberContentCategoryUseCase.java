package com.hapbang.member.app;

import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.hapbang.member.domain.ContentCategory;
import com.hapbang.member.out.ContentCategoryCount;
import com.hapbang.member.out.ContentCategoryRepository;
import com.hapbang.member.out.UserRepository;

import lombok.RequiredArgsConstructor;

/** 콘텐츠 분야 목록 조회 (#711) */
@Service
@RequiredArgsConstructor
public class MemberContentCategoryUseCase {

    private final ContentCategoryRepository contentCategoryRepository;
    private final UserRepository userRepository;

    /**
     * 분야 목록 (분야 고르는 칸·검색 필터 후보). 새로 고를 수 있는(active) 분야만, 정상 인플루언서가 고른 수가 많은 순, 같으면 이름순.
     * 이름은 Java 문자열 순서(유니코드)로 정렬해 DB 정렬 규칙(collation)과 상관없이 같다: 한글은 가나다순이고 영문으로 시작하면 한글보다 앞이다.
     * 분야가 수십 개라 모두 읽어 정렬한다
     */
    public List<ContentCategory> list() {
        Map<String, Long> counts = userRepository.countInfluencersByContentCategory().stream()
                .collect(toUnmodifiableMap(ContentCategoryCount::code, ContentCategoryCount::count));
        return contentCategoryRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparingLong((ContentCategory category) -> counts.getOrDefault(category.getCode(), 0L))
                        .reversed()
                        .thenComparing(ContentCategory::getName))
                .toList();
    }

    /**
     * 주어진 분야 코드 → 이름. 응답에 분야 이름을 붙일 때 쓴다. 이미 고른 회원의 값은 계속 보이므로 목록에서 꺼진 분야도 준다 (Q50)
     */
    public Map<String, String> names(Collection<String> codes) {
        return contentCategoryRepository.findAllById(Set.copyOf(codes)).stream()
                .collect(toUnmodifiableMap(ContentCategory::getCode, ContentCategory::getName));
    }
}

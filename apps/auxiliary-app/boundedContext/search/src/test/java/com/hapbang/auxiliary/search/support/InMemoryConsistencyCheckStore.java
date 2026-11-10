package com.hapbang.auxiliary.search.support;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.hapbang.auxiliary.search.out.ConsistencyCheck;
import com.hapbang.auxiliary.search.out.ConsistencyCheckStore;

/** DB 없이 정합성 점검 결과를 메모리에 두는 가짜(#772). SQL은 {@code ConsistencyCheckStoreTest}가 본다 */
public class InMemoryConsistencyCheckStore extends ConsistencyCheckStore {

    private final Map<String, ConsistencyCheck> checks = new HashMap<>();

    public InMemoryConsistencyCheckStore() {
        super(null);
    }

    @Override
    public Optional<ConsistencyCheck> find(String target) {
        return Optional.ofNullable(checks.get(target));
    }

    @Override
    public void save(ConsistencyCheck check) {
        checks.put(check.target(), check);
    }

    @Override
    public void clear(String target) {
        checks.remove(target);
    }
}

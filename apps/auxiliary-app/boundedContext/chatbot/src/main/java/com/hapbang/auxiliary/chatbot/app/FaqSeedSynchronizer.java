package com.hapbang.auxiliary.chatbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.auxiliary.chatbot.domain.Faq;
import com.hapbang.auxiliary.chatbot.domain.FaqRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시드 파일과 DB를 code로 맞춘다(#715). 없으면 넣고, 다르면 고치고, 같으면 건너뛴다. 시드에서 빠진 code는 DB에서 지운다.
 * 교안의 "count > 0이면 건너뜀"은 시드를 고쳐도 반영되지 않아 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FaqSeedSynchronizer {

    private final FaqRepository faqRepository;

    /**
     * @return 결과(지운 code 포함). 색인에서 지우는 일은 {@link FaqIndexer}가 ES 문서와 DB를 비교해 따로 한다
     */
    @Transactional
    public SeedResult sync(List<FaqSeed> seeds) {
        Map<String, Faq> existing = faqRepository.findAll().stream()
                .collect(Collectors.toMap(Faq::getCode, Function.identity()));
        int inserted = 0;
        int updated = 0;
        for (int i = 0; i < seeds.size(); i++) {
            FaqSeed seed = seeds.get(i);
            Faq faq = existing.remove(seed.code());
            if (faq == null) {
                faqRepository.save(Faq.create(seed.code(), seed.content(i)));
                inserted++;
            } else if (faq.update(seed.content(i))) {
                updated++;
            }
        }
        List<String> removed = new ArrayList<>(existing.keySet());
        faqRepository.deleteAll(existing.values());
        log.info("FAQ 시드를 맞췄다 inserted={}, updated={}, removed={}", inserted, updated, removed.size());
        return new SeedResult(inserted, updated, removed);
    }

    /** 시드 맞추기 결과 */
    public record SeedResult(int inserted, int updated, List<String> removedCodes) {
    }
}

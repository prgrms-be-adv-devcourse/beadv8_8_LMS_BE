package com.hapbang.auxiliary.chatbot.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FaqRepository extends JpaRepository<Faq, Long> {

    List<Faq> findAllByOrderBySortOrderAsc();

    List<Faq> findByCategoryOrderBySortOrderAsc(FaqCategory category);

    List<Faq> findByCodeIn(Collection<String> codes);

    /** 다시 색인해야 하는 FAQ(아직 색인하지 않았거나 내용이 바뀜) */
    @Query("select f from Faq f where f.indexedHash is null or f.indexedHash <> f.contentHash order by f.sortOrder")
    List<Faq> findNeedsIndexing();

    /** 색인이 비었거나 새로 만들어졌을 때 모두 다시 색인하게 한다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Faq f set f.indexedHash = null, f.indexedAt = null")
    int clearAllIndexed();
}

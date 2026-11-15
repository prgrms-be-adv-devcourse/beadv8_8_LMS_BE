package com.hapbang.member.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import com.hapbang.member.domain.ContentCategory;
import com.hapbang.testsupport.RepositoryTest;

@RepositoryTest
class ContentCategoryRepositoryTest {

    @Autowired
    ContentCategoryRepository contentCategoryRepository;

    /** 두 번째 반복은 테스트 DB 정리(truncate) 뒤에 실행되므로, 비운 뒤 처음 목록을 다시 넣는지도 확인한다 */
    @RepeatedTest(2)
    @DisplayName("처음 목록 23개가 모두 고를 수 있는 상태로 들어 있다")
    void initialCategories() {
        List<ContentCategory> categories = contentCategoryRepository.findAll();

        assertThat(categories).extracting(ContentCategory::getCode).containsExactlyInAnyOrder(
                "GAME", "MUKBANG", "BEAUTY", "JUST_CHATTING", "MUSIC", "SPORTS", "EDUCATION", "DAILY_LIFE",
                "LEAGUE_OF_LEGENDS", "VALORANT", "MAPLESTORY", "BATTLEGROUNDS", "LOST_ARK", "OVERWATCH",
                "MINECRAFT", "FC_ONLINE", "STARCRAFT", "DUNGEON_FIGHTER", "SINGING", "INSTRUMENT", "COOKING",
                "TRAVEL", "DRAWING");
        assertThat(categories).allMatch(ContentCategory::isActive);
        assertThat(contentCategoryRepository.findById("EDUCATION")).get()
                .extracting(ContentCategory::getName).isEqualTo("교육·지식");
    }
}

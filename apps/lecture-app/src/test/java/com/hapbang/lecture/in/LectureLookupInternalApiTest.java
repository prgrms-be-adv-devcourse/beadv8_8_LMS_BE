package com.hapbang.lecture.in;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.support.GatewayLogin;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.dto.LectureLookupDto;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.testsupport.IntegrationTest;

// 강의 조회 내부 API(쿠폰이 호출)를 내부 API 보안 체인부터 DB까지 관통해 검증한다
@IntegrationTest
@AutoConfigureMockMvc
class LectureLookupInternalApiTest {

    private static final String LOOKUP = "/api/v1/internal/lectures/{lectureId}";
    private static final String BATCH = "/api/v1/internal/lectures";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 내부_키로_판매자_가격_판매_상태_제목을_조회한다() throws Exception {
        Lecture onSale = lectureRepository.save(onSaleLecture());
        Lecture pending = lectureRepository.save(pendingLecture());

        mockMvc.perform(internal(get(LOOKUP, onSale.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("강의를 조회했습니다."))
                .andExpect(jsonPath("$.data.lectureId").value(onSale.getId()))
                .andExpect(jsonPath("$.data.sellerId").value(SELLER_ID))
                .andExpect(jsonPath("$.data.price").value(PRICE_HP))
                .andExpect(jsonPath("$.data.saleStatus").value("ON_SALE"))
                .andExpect(jsonPath("$.data.title").value(TITLE));
        mockMvc.perform(internal(get(LOOKUP, pending.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("PENDING_INSPECTION"));
    }

    @Test
    void 없는_강의와_삭제된_강의는_404_LECTURE_NOT_FOUND다() throws Exception {
        Lecture deleted = lectureRepository.save(onSaleLecture());
        transactionTemplate.executeWithoutResult(status ->
                lectureRepository.findById(deleted.getId()).orElseThrow().delete(SELLER_ID, NOW));

        mockMvc.perform(internal(get(LOOKUP, 999_999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
        mockMvc.perform(internal(get(LOOKUP, deleted.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LECTURE_NOT_FOUND"));
    }

    @Test
    void 내부_키가_없거나_회원_로그인만_있으면_본문_없이_401이다() throws Exception {
        Lecture lecture = lectureRepository.save(onSaleLecture());

        mockMvc.perform(get(LOOKUP, lecture.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(get(LOOKUP, lecture.getId()).with(GatewayLogin.as(SELLER_ID, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BATCH).param("ids", String.valueOf(lecture.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void 일괄_조회는_요청한_ID마다_키를_두고_판매_상태로_거르지_않으며_없거나_삭제된_강의는_null이다() throws Exception {
        Lecture onSale = lectureRepository.save(onSaleLecture());
        Lecture pending = lectureRepository.save(pendingLecture());
        Lecture rejected = lectureRepository.save(rejectedLecture());
        Lecture deleted = lectureRepository.save(onSaleLecture());
        transactionTemplate.executeWithoutResult(status ->
                lectureRepository.findById(deleted.getId()).orElseThrow().delete(SELLER_ID, NOW));
        long missing = deleted.getId() + 1_000;

        // 중복 ID는 한 번만 키가 된다. ids 반복은 쿠폰 클라이언트가 보내는 모양이다(쉼표 목록은 아래 개수 검사에서 본다)
        mockMvc.perform(internal(get(BATCH)
                        .param("ids", String.valueOf(onSale.getId()), String.valueOf(pending.getId()),
                                String.valueOf(rejected.getId()), String.valueOf(deleted.getId()),
                                String.valueOf(missing), String.valueOf(onSale.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.message").value("강의를 조회했습니다."))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data['%d'].lectureId".formatted(onSale.getId())).value(onSale.getId()))
                .andExpect(jsonPath("$.data['%d'].sellerId".formatted(onSale.getId())).value(SELLER_ID))
                .andExpect(jsonPath("$.data['%d'].price".formatted(onSale.getId())).value(PRICE_HP))
                .andExpect(jsonPath("$.data['%d'].saleStatus".formatted(onSale.getId())).value("ON_SALE"))
                .andExpect(jsonPath("$.data['%d'].title".formatted(onSale.getId())).value(TITLE))
                .andExpect(jsonPath("$.data['%d'].saleStatus".formatted(pending.getId()))
                        .value("PENDING_INSPECTION"))
                .andExpect(jsonPath("$.data['%d'].saleStatus".formatted(rejected.getId())).value("REJECTED"))
                .andExpect(jsonPath("$.data['%d']".formatted(deleted.getId())).value(nullValue()))
                .andExpect(jsonPath("$.data['%d']".formatted(missing)).value(nullValue()));
    }

    @Test
    void 일괄_조회는_ids가_0개이거나_100개를_넘거나_숫자가_아니면_400이다() throws Exception {
        String hundredOne = String.join(",", Collections.nCopies(LectureLookupDto.MAX_BATCH_SIZE + 1, "1"));

        mockMvc.perform(internal(get(BATCH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(get(BATCH).param("ids", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(get(BATCH).param("ids", hundredOne)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(get(BATCH).param("ids", "1,abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(internal(get(BATCH)
                        .param("ids", String.join(",", Collections.nCopies(LectureLookupDto.MAX_BATCH_SIZE, "1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiHeaders.API_KEY, IntegrationTest.INTERNAL_API_KEY);
    }
}

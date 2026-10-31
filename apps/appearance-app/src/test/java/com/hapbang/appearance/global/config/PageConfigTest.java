package com.hapbang.appearance.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.support.GatewayLogin;
import com.hapbang.shared.member.domain.Role;

// 컨트롤러가 Page를 반환하면 PagedModel 형태로 응답하는지 검증한다 (2026-10-12 회의: Pageable 표준).
@ContextConfiguration(classes = com.hapbang.testsupport.WebSliceTestConfiguration.class)
@WebMvcTest(controllers = PageConfigTest.SampleController.class, properties = "spring.config.name=hapbang-module-test")
@Import({SecurityConfig.class, PageConfig.class, PageConfigTest.SampleController.class})
class PageConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void Page를_반환하면_content와_page_정보로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/samples").param("page", "1").param("size", "2")
                        .with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0]").value("c"))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void 페이지_크기는_최대_100으로_줄인다() throws Exception {
        mockMvc.perform(get("/api/v1/samples").param("size", "101")
                        .with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @Test
    void 페이지_크기를_보내지_않으면_20개씩_나눈다() throws Exception {
        mockMvc.perform(get("/api/v1/samples").with(GatewayLogin.as(1L, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(20));
    }

    @org.springframework.boot.test.context.TestComponent
    @RestController
    static class SampleController {

        @GetMapping("/api/v1/samples")
        Page<String> samples(Pageable pageable) {
            return new PageImpl<>(List.of("c"), pageable, 3);
        }
    }
}

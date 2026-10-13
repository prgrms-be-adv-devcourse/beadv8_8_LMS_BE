package com.hapbang.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.filter.FormContentFilter;

import com.hapbang.testsupport.PostgresTestcontainersConfig;

// 실제 설정(application.yaml의 spring.mvc.formcontent)을 검증한다
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
class FormContentFilterTest {

    @Autowired
    ApplicationContext context;

    @Test
    void PUT_PATCH_DELETE의_form_본문을_인증_전에_메모리로_읽는_필터를_쓰지_않는다() {
        assertThat(context.getBeansOfType(FormContentFilter.class)).isEmpty();
    }
}

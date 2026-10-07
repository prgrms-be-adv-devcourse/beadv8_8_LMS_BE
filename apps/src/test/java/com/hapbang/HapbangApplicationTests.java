package com.hapbang;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.hapbang.testsupport.PostgresTestcontainersConfig;

@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
class HapbangApplicationTests {

    @Test
    void contextLoads() {
    }

}

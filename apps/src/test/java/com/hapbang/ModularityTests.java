package com.hapbang;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import com.hapbang.HapbangApplication;

/**
 * 모든 모듈이 classpath에 있는 apps에서만 실행할 수 있는 모듈 경계 검증.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(HapbangApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        new Documenter(modules).writeDocumentation();
    }

}

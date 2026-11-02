package com.hapbang.billing.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 모듈 간 HTTP 계약(shared.billing.dto)은 in 패키지(내부 API Controller와 변환 클래스)만 다루고, app·domain·out은
// Billing 내부 Command·결과를 쓰는지 확인한다 (#494). shared.billing.domain의 Enum·오류 코드는 계속 쓸 수 있다.
class BillingSharedDtoAccessTest {

    @Test
    @DisplayName("billing app·domain·out은 shared.billing.dto를 참조하지 않는다")
    void onlyInPackageUsesSharedDto() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.hapbang.billing");

        noClasses().that().resideInAnyPackage("com.hapbang.billing.app..", "com.hapbang.billing.domain..",
                        "com.hapbang.billing.out..")
                .should().dependOnClassesThat().resideInAPackage("com.hapbang.shared.billing.dto..")
                .because("HoldFacade·UseCase가 shared HTTP 계약에 묶이지 않도록 in.BillingCommands가 내부 Command로 바꿔 넘긴다")
                .check(productionClasses);
    }
}

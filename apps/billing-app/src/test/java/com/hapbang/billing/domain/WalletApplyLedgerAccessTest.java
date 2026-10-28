package com.hapbang.billing.domain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.billing.app.LedgerRecordUseCase;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

// 원장 기록 없이 지갑 잔액만 바뀌지 않도록, 실행 코드에서 Wallet.applyLedger 호출 경로를 LedgerRecordUseCase로 제한한다.
class WalletApplyLedgerAccessTest {

    @Test
    @DisplayName("LedgerRecordUseCase 외의 실행 코드는 Wallet.applyLedger를 호출하지 않는다")
    void onlyLedgerRecordUseCaseAppliesLedger() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.hapbang");

        noClasses().that().doNotHaveFullyQualifiedName(LedgerRecordUseCase.class.getName())
                .should().callMethod(Wallet.class, "applyLedger", PointLedger.class)
                .because("지갑 잔액은 원장 기록과 같은 트랜잭션에서 LedgerRecordUseCase를 통해서만 바뀌어야 한다")
                .check(productionClasses);
    }
}

package com.hapbang.chat.out;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.hapbang.chat.app.ChatUserUseCase;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 바깥 어댑터({@code out})는 app의 계약({@code app/port}·{@code app/dto}·{@code app/exception})만 쓴다. app 바로 아래의 클래스
 * (업무 서비스 계약·구현, 조정 클래스, Facade)에 기대지 않는다. 패키지가 정확히 {@code com.hapbang.chat.app}인 클래스만 막는다.
 */
class ChatOutBoundaryTest {

    private static final ArchRule OUT_USES_ONLY_APP_CONTRACTS = noClasses()
            .that().resideInAPackage("com.hapbang.chat.out..")
            .should().dependOnClassesThat().resideInAPackage("com.hapbang.chat.app")
            .because("out은 app/port·app/dto·app/exception의 계약만 쓴다");

    @Test
    void out_어댑터는_app_바로_아래_클래스에_의존하지_않는다() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.hapbang.chat");

        OUT_USES_ONLY_APP_CONTRACTS.check(classes);
    }

    @Test
    void app_바로_아래_클래스에_의존하는_out_클래스가_있으면_규칙이_잡는다() {
        JavaClasses violating = new ClassFileImporter().importClasses(ViolatingAdapter.class);

        assertThatThrownBy(() -> OUT_USES_ONLY_APP_CONTRACTS.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ChatUserUseCase.class.getName());
    }

    /** 규칙 위반 예: out 어댑터가 app 조정 클래스를 받는다 */
    @SuppressWarnings("unused")
    private static final class ViolatingAdapter {

        private final ChatUserUseCase chatUserUseCase;

        ViolatingAdapter(ChatUserUseCase chatUserUseCase) {
            this.chatUserUseCase = chatUserUseCase;
        }
    }
}

package com.hapbang.auxiliary.chatbot.in.dto;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 글자 수를 코드 포인트로 센 {@code @Size} (#835). {@code @Size}는 UTF-16 단위로 세서 이모지 하나를 두 글자로 본다.
 * 챗봇은 글자 수를 코드 포인트로 센다(이전 대화 검사·글 자르기·관심사 정규식). 오류 메시지는 {@code @Size}와 같다. null은 통과한다.
 */
@Documented
@Constraint(validatedBy = CodePointSize.Validator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
public @interface CodePointSize {

    String message() default "{jakarta.validation.constraints.Size.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    class Validator implements ConstraintValidator<CodePointSize, CharSequence> {

        private int min;
        private int max;

        @Override
        public void initialize(CodePointSize constraint) {
            min = constraint.min();
            max = constraint.max();
        }

        @Override
        public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            int length = Character.codePointCount(value, 0, value.length());
            return length >= min && length <= max;
        }
    }
}

package dev.harshaattili.reconledger;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Text that can be represented without replacement in both supported databases. */
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE, RECORD_COMPONENT})
@Retention(RUNTIME)
@Constraint(validatedBy = ReviewText.Validator.class)
public @interface ReviewText {
    String message() default "must contain Unicode scalar values without NUL";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    final class Validator implements ConstraintValidator<ReviewText, String> {
        @Override public boolean isValid(String value, ConstraintValidatorContext context) {
            // Requiredness and length remain separate constraints.
            if (value == null) return true;
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (current == 0 || Character.isLowSurrogate(current)) return false;
                if (Character.isHighSurrogate(current)) {
                    if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
                }
            }
            return true;
        }
    }
}

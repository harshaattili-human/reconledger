package dev.harshaattili.reconledger;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTextTest {
    private final ReviewText.Validator validator = new ReviewText.Validator();

    @Test void rejectsUnpairedSurrogatesAndNulAtEveryPosition() {
        for (String bad : List.of("\u0000", "\ud800", "\udfff", "\ud800x", "\udfff\ud800", "\ud800\ud800")) {
            assertThat(validator.isValid(bad, null)).isFalse();
            assertThat(validator.isValid("before" + bad + "after", null)).isFalse();
        }
    }

    @Test void preservesScalarValuesWithoutNormalizationOrNewLengthRules() {
        for (String good : List.of("", "Café", "e\u0301", "\ud83d\ude80", "a\ud83d\ude80z", "line\n\tend", "\uffff")) {
            assertThat(validator.isValid(good, null)).isTrue();
        }
        assertThat(validator.isValid(null, null)).isTrue();
    }
}

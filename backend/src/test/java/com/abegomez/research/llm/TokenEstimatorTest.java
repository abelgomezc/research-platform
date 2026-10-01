package com.abegomez.research.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    @DisplayName("Estima aproximadamente cuatro caracteres por token")
    void estimatesFourCharsPerToken() {
        assertThat(TokenEstimator.estimate("12345678")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("1234")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("12345")).isEqualTo(2);
    }

    @Test
    @DisplayName("Un texto vacio o nulo no consume tokens")
    void handlesEmptyInput() {
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate(null)).isZero();
    }
}

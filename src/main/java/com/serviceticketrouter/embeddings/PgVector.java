package com.serviceticketrouter.embeddings;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public final class PgVector {
    private PgVector() {
    }

    public static String toLiteral(List<BigDecimal> embedding) {
        return embedding.stream()
                .map(BigDecimal::toPlainString)
                .collect(Collectors.joining(",", "[", "]"));
    }
}

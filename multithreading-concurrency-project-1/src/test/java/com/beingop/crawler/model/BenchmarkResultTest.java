package com.beingop.crawler.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkResultTest {

    @Test
    void throughput_calculatesCorrectly() {
        BenchmarkResult result = new BenchmarkResult(100, 2000);
        assertEquals(50.0, result.throughput(), 0.001);
    }

    @Test
    void throughput_returnsZero_whenElapsedIsZero() {
        BenchmarkResult result = new BenchmarkResult(100, 0);
        assertEquals(0.0, result.throughput());
    }
}

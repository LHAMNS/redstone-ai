package com.redstoneai.test;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestSuiteTest {

    @Test
    void builderCreatesImmutableSuite() {
        TestSuite suite = new TestSuite.Builder("and_gate")
                .addCase(new TestCase("case1", Map.of("A", 0, "B", 0), Map.of("OUT", 0), 10))
                .addCase(new TestCase("case2", Map.of("A", 15, "B", 15), Map.of("OUT", 15), 10))
                .build();

        assertEquals("and_gate", suite.getName());
        assertEquals(2, suite.size());
        assertEquals("case1", suite.getCases().get(0).name());
    }

    @Test
    void testCaseDefaults() {
        TestCase tc = new TestCase(Map.of("A", 15), Map.of("OUT", 0), 5);
        assertEquals("", tc.name());
        assertEquals(5, tc.ticks());
        assertEquals(15, tc.inputs().get("A"));
        assertEquals(0, tc.expected().get("OUT"));
    }

    @Test
    void testResultSummarize() {
        TestResult pass = new TestResult(
                new TestCase("pass", Map.of("A", 15), Map.of("OUT", 15), 10),
                true, Map.of("OUT", 15), 10);
        TestResult fail = new TestResult(
                new TestCase("fail", Map.of("A", 0), Map.of("OUT", 15), 10),
                false, Map.of("OUT", 0), 10);

        String summary = TestRunner.summarize(List.of(pass, fail));
        assertTrue(summary.contains("1/2 passed"));
        assertTrue(summary.contains("1 FAILED"));
        assertTrue(summary.contains("FAIL"));
    }

    @Test
    void allPassSummary() {
        TestResult pass1 = new TestResult(
                new TestCase(Map.of(), Map.of(), 1), true, Map.of(), 1);
        TestResult pass2 = new TestResult(
                new TestCase(Map.of(), Map.of(), 1), true, Map.of(), 1);

        String summary = TestRunner.summarize(List.of(pass1, pass2));
        assertTrue(summary.contains("2/2 passed"));
        assertFalse(summary.contains("FAILED"));
    }

    @Test
    void emptySuite() {
        TestSuite suite = new TestSuite.Builder("empty").build();
        assertEquals(0, suite.size());
        assertTrue(suite.getCases().isEmpty());
    }
}

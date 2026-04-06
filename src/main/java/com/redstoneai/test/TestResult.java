package com.redstoneai.test;

import java.util.Map;

/**
 * The result of running a single {@link TestCase}.
 *
 * @param testCase     the original test case
 * @param passed       whether all expected outputs matched
 * @param actual       label → actual power level for OUTPUT IO markers
 * @param ticksElapsed actual number of ticks stepped
 */
public record TestResult(
        TestCase testCase,
        boolean passed,
        Map<String, Integer> actual,
        int ticksElapsed
) {}

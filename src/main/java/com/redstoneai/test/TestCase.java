package com.redstoneai.test;

import java.util.Map;

/**
 * A single test case definition: set inputs to given power levels,
 * step a number of ticks, then verify outputs match expected values.
 *
 * @param name     human-readable test name (optional, for reporting)
 * @param inputs   label → power level for INPUT IO markers
 * @param expected label → expected power level for OUTPUT IO markers
 * @param ticks    number of ticks to step before checking outputs
 */
public record TestCase(
        String name,
        Map<String, Integer> inputs,
        Map<String, Integer> expected,
        int ticks
) {
    public TestCase(Map<String, Integer> inputs, Map<String, Integer> expected, int ticks) {
        this("", inputs, expected, ticks);
    }
}

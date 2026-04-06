package com.redstoneai.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered collection of {@link TestCase}s to be run against a single workspace.
 * Immutable after construction.
 */
public final class TestSuite {
    private final String name;
    private final List<TestCase> cases;

    public TestSuite(String name, List<TestCase> cases) {
        this.name = name;
        this.cases = List.copyOf(cases);
    }

    public String getName() {
        return name;
    }

    public List<TestCase> getCases() {
        return cases;
    }

    public int size() {
        return cases.size();
    }

    /**
     * Builder for constructing a TestSuite incrementally.
     */
    public static class Builder {
        private final String name;
        private final List<TestCase> cases = new ArrayList<>();

        public Builder(String name) {
            this.name = name;
        }

        public Builder addCase(TestCase testCase) {
            cases.add(testCase);
            return this;
        }

        public TestSuite build() {
            return new TestSuite(name, cases);
        }
    }
}

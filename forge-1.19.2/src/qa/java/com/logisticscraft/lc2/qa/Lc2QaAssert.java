package com.logisticscraft.lc2.qa;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Structured assertion and metrics recorder for the Forge 1.19.2 live-server QA harness.
 */
final class Lc2QaAssert {

    private final String phase;
    private final Map<String, String> checks = new LinkedHashMap<>();
    private final Map<String, String> metrics = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    Lc2QaAssert(String phase) {
        this.phase = phase;
    }

    void check(String key, boolean condition, String detail) {
        if (condition) {
            checks.put(key, "PASS (" + detail + ")");
            Lc2QaMod.LOGGER.info("[LC2-QA] PASS {}: {}", key, detail);
        } else {
            String msg = key + ": " + detail;
            checks.put(key, "FAIL (" + detail + ")");
            failures.add(msg);
            Lc2QaMod.LOGGER.error("[LC2-QA] FAIL {}: {}", key, detail);
            throw new AssertionError("[LC2-QA] " + msg);
        }
    }

    void checkEquals(String key, Object expected, Object actual) {
        boolean eq = expected == null ? actual == null : expected.equals(actual);
        check(key, eq, "expected=" + expected + ", actual=" + actual);
    }

    void checkClose(String key, double expected, double actual, double eps) {
        boolean ok = Double.isFinite(actual) && Math.abs(expected - actual) <= eps;
        check(key, ok, String.format("expected=%.4f, actual=%.4f, eps=%.4f", expected, actual, eps));
    }

    void metric(String key, Object value) {
        metrics.put(key, String.valueOf(value));
    }

    int passedCount() {
        return checks.size() - failures.size();
    }

    int failedCount() {
        return failures.size();
    }

    void writePassedReport(Path outFile, Map<String, String> extraProps) throws IOException {
        Properties props = new Properties();
        props.setProperty("phase", phase);
        props.setProperty("status", failures.isEmpty() ? "PASSED" : "FAILED");
        props.setProperty("passedChecks", Integer.toString(passedCount()));
        props.setProperty("failedChecks", Integer.toString(failedCount()));
        props.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        for (Map.Entry<String, String> e : checks.entrySet()) {
            props.setProperty("check." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : metrics.entrySet()) {
            props.setProperty("metric." + e.getKey(), e.getValue());
        }
        if (extraProps != null) {
            for (Map.Entry<String, String> e : extraProps.entrySet()) {
                props.setProperty(e.getKey(), e.getValue());
            }
        }
        try (Writer w = Files.newBufferedWriter(outFile)) {
            props.store(w, "Transitera Forge 1.19.2 QA Report (" + phase + ")");
        }
    }

    void writeFailureReport(Path reportFile, Path failureTxt, Throwable error) {
        try {
            Properties props = new Properties();
            props.setProperty("phase", phase);
            props.setProperty("status", "FAILED");
            props.setProperty("passedChecks", Integer.toString(passedCount()));
            props.setProperty("failedChecks", Integer.toString(Math.max(1, failedCount())));
            props.setProperty("error", String.valueOf(error));
            for (Map.Entry<String, String> e : checks.entrySet()) {
                props.setProperty("check." + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, String> e : metrics.entrySet()) {
                props.setProperty("metric." + e.getKey(), e.getValue());
            }
            try (Writer w = Files.newBufferedWriter(reportFile)) {
                props.store(w, "Transitera Forge 1.19.2 QA Failure (" + phase + ")");
            }
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            StringBuilder sb = new StringBuilder();
            sb.append("phase=").append(phase).append('\n');
            sb.append("passedChecks=").append(passedCount()).append('\n');
            sb.append("failedChecks=").append(Math.max(1, failedCount())).append('\n');
            sb.append("error=").append(error).append('\n');
            sb.append("stackTrace:\n").append(sw).append('\n');
            sb.append("completedChecks:\n");
            for (Map.Entry<String, String> e : checks.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
            Files.writeString(failureTxt, sb.toString());
        } catch (IOException ioe) {
            Lc2QaMod.LOGGER.error("[LC2-QA] Failed writing failure report", ioe);
        }
    }
}

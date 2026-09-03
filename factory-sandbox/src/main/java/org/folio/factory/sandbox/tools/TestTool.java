package org.folio.factory.sandbox.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.springframework.stereotype.Component;

@Component
public class TestTool {

  static final long TEST_TIMEOUT_SEC = 900L;
  private static final Pattern SUREFIRE_LINE = Pattern.compile(
      "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");
  private static final String REPO_DIR = "repo";

  private final SandboxService sandboxService;

  public TestTool(SandboxService sandboxService) {
    this.sandboxService = sandboxService;
  }

  public ToolResult test(SandboxHandle handle, String module) {
    String mvnCommand = "cd " + REPO_DIR + " && mvn "
        + (module == null || module.isBlank() ? "" : "-pl " + Shell.quote(module) + " -am ")
        + "test -B";
    CommandResult result = sandboxService.exec(handle, mvnCommand, TEST_TIMEOUT_SEC);
    SurefireSummary summary = parse(result.stdout() + "\n" + result.stderr());
    String output = OutputLimiter.truncate(
        "[summary]\n" + summary.text() + "\n\n[maven]\n" + result.stdout());
    if (result.ok() && summary.allPassed()) {
      return ToolResult.success(output);
    }
    String error = result.ok()
        ? "tests failed: " + summary.text()
        : "mvn exited with code " + result.exitCode()
            + (summary.found() ? "; " + summary.text() : "");
    return ToolResult.failure(output, error);
  }

  static SurefireSummary parse(String mavenOutput) {
    int tests = 0;
    int failures = 0;
    int errors = 0;
    int skipped = 0;
    int aggTests = 0;
    int aggFailures = 0;
    int aggErrors = 0;
    int aggSkipped = 0;
    boolean perClassFound = false;
    boolean aggregateFound = false;
    for (String line : mavenOutput.split("\n")) {
      Matcher matcher = SUREFIRE_LINE.matcher(line);
      if (!matcher.find()) {
        continue;
      }
      int t = Integer.parseInt(matcher.group(1));
      int f = Integer.parseInt(matcher.group(2));
      int e = Integer.parseInt(matcher.group(3));
      int s = Integer.parseInt(matcher.group(4));
      if (line.contains("-- in")) {
        perClassFound = true;
        tests += t;
        failures += f;
        errors += e;
        skipped += s;
      } else {
        aggregateFound = true;
        aggTests += t;
        aggFailures += f;
        aggErrors += e;
        aggSkipped += s;
      }
    }
    if (perClassFound) {
      return new SurefireSummary(tests, failures, errors, skipped, true);
    }
    return new SurefireSummary(aggTests, aggFailures, aggErrors, aggSkipped, aggregateFound);
  }

  record SurefireSummary(int tests, int failures, int errors, int skipped, boolean found) {

    boolean allPassed() {
      return found && failures == 0 && errors == 0;
    }

    String text() {
      return found
          ? "Tests run: " + tests + ", Failures: " + failures + ", Errors: " + errors
              + ", Skipped: " + skipped
          : "no surefire results parsed";
    }
  }
}

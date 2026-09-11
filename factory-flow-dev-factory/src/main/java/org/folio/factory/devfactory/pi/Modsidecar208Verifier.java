package org.folio.factory.devfactory.pi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.folio.factory.sandbox.tools.Shell;
import tools.jackson.databind.JsonNode;

/**
 * Curated, task-local checks for the first real FOLIO evaluation.
 *
 * <p>This is intentionally not a general benchmark grader.  It checks the
 * published MODSIDECAR-208 contract, then uses the repository's resolved
 * SmallRye Config version to exercise the actual placeholder in two clean
 * JVMs.  The checker source and classes live in the verifier's temporary
 * directory, never in the candidate repository.</p>
 */
public final class Modsidecar208Verifier {
  public static final String TASK_ID = "MODSIDECAR-208";
  public static final String REPOSITORY = "folio-org/folio-module-sidecar";
  public static final String BASE_REVISION = "c13e0383d9283ef554c195cf5357bb3c6eeb4e65";
  public static final String CHECKER_VERSION = "smallrye-config-core:3.17.2";

  private static final String CHECKER_DIR = "/tmp/factory-smallrye-checker";
  private static final String PROPERTY = "quarkus.thread-pool.max-threads";
  private static final String ENVIRONMENT = "QUARKUS_THREAD_POOL_MAX_THREADS";
  private static final String CONFIG_PATH = "/workspace/repo/src/main/resources/application.properties";
  private static final Pattern REPORT_LINE = Pattern.compile(
      "^REPORT\\t([^\\t]+)\\ttests=(\\d+)\\tfailures=(\\d+)\\terrors=(\\d+)\\tskipped=(\\d+)$");

  public boolean applies(String taskId, String repository, String baseRevision) {
    return TASK_ID.equals(taskId) && REPOSITORY.equals(repository)
        && BASE_REVISION.equalsIgnoreCase(baseRevision);
  }

  public CommandResult runUnitTests(SandboxService sandboxes, SandboxHandle handle,
                                    long timeoutSec) {
    return sandboxes.exec(handle, "cd repo && mvn -B -ntp clean test", timeoutSec);
  }

  /** The clean base must resolve the property as absent, after the checker itself compiles. */
  public CommandResult verifyBaseIsRed(SandboxService sandboxes, SandboxHandle handle,
                                       long timeoutSec) {
    return sandboxes.exec(handle, probeCommand("BASELINE"), timeoutSec);
  }

  /** Check source scope, the documented contract and both SmallRye resolution paths. */
  public CommandResult verifyCandidate(SandboxService sandboxes, SandboxHandle handle,
                                       String baseRevision, long timeoutSec) {
    String base = Shell.quote(baseRevision);
    String paths = "cd repo && git add -A && git diff --cached --name-only " + base
        + " | while IFS= read -r path; do case \"$path\" in "
        + "src/main/resources/application.properties|README.md|NEWS.md|src/test/*) ;; "
        + "*) echo UNSUPPORTED_PATH:$path >&2; exit 7 ;; esac; done";
    String source = "grep -Eq " + Shell.quote("^quarkus\\.thread-pool\\.max-threads=\\$\\{"
        + ENVIRONMENT + ":8\\}[[:space:]]*$")
        + " src/main/resources/application.properties && grep -Eq "
        + Shell.quote("^\\|[[:space:]]*" + ENVIRONMENT
        + "[[:space:]]*\\|[[:space:]]*8[[:space:]]*\\|[[:space:]]*false[[:space:]]*\\|.*[Tt]hread")
        + " README.md";
    return sandboxes.exec(handle, paths + " && " + source + " && "
        + probeCommand("CANDIDATE"), timeoutSec);
  }

  /** Require a fresh non-empty report with a positive test count. */
  public CommandResult summarizeReports(SandboxService sandboxes, SandboxHandle handle,
                                         long timeoutSec) {
    String command = "cd repo && files=$(find . -path '*/target/surefire-reports/TEST-*.xml' "
        + "-type f -size +0c | sort) && test -n \"$files\" && total=0 && "
        + "for file in $files; do tests=$(sed -n 's/.*tests=\"\\([0-9][0-9]*\\)\".*/\\1/p' \"$file\" | head -1); "
        + "failures=$(sed -n 's/.*failures=\"\\([0-9][0-9]*\\)\".*/\\1/p' \"$file\" | head -1); "
        + "errors=$(sed -n 's/.*errors=\"\\([0-9][0-9]*\\)\".*/\\1/p' \"$file\" | head -1); "
        + "skipped=$(sed -n 's/.*skipped=\"\\([0-9][0-9]*\\)\".*/\\1/p' \"$file\" | head -1); "
        + "test -n \"$tests\" && test -n \"$failures\" && test -n \"$errors\" && test -n \"$skipped\" || { echo 'invalid surefire report' \"$file\" >&2; exit 9; }; "
        + "total=$((total + tests)); printf 'REPORT\\t%s\\ttests=%s\\tfailures=%s\\terrors=%s\\tskipped=%s\\n' \"$file\" \"$tests\" \"$failures\" \"$errors\" \"$skipped\"; done; "
        + "test \"$total\" -gt 0";
    return sandboxes.exec(handle, command, timeoutSec);
  }

  /** Parse the bounded report summary into identity and execution facts. */
  public ReportSummary parseReportSummary(CommandResult result) {
    Map<String, Integer> testsByReport = new LinkedHashMap<>();
    int totalTests = 0;
    int failures = 0;
    int errors = 0;
    int skipped = 0;
    boolean valid = result != null && result.stdout() != null;
    if (valid) {
      for (String line : result.stdout().split("\\R")) {
        if (line.isBlank()) {
          continue;
        }
        Matcher matcher = REPORT_LINE.matcher(line);
        if (!matcher.matches()) {
          valid = false;
          continue;
        }
        try {
          int tests = Integer.parseInt(matcher.group(2));
          if (testsByReport.put(matcher.group(1), tests) != null) {
            valid = false;
          }
          totalTests += tests;
          failures += Integer.parseInt(matcher.group(3));
          errors += Integer.parseInt(matcher.group(4));
          skipped += Integer.parseInt(matcher.group(5));
        } catch (NumberFormatException e) {
          valid = false;
        }
      }
    }
    return new ReportSummary(valid && !testsByReport.isEmpty(), totalTests, failures, errors,
        skipped, testsByReport);
  }

  public record ReportSummary(boolean valid, int totalTests, int failures, int errors,
                              int skipped, Map<String, Integer> testsByReport) {
    public ReportSummary {
      testsByReport = testsByReport == null ? Map.of() : Map.copyOf(testsByReport);
    }

    public Map<String, Object> asMap() {
      return Map.of("valid", valid, "totalTests", totalTests, "failures", failures,
          "errors", errors, "skipped", skipped, "testsByReport", testsByReport);
    }

    public static ReportSummary fromJson(JsonNode node) {
      Map<String, Integer> reports = new LinkedHashMap<>();
      JsonNode reportNode = node == null ? null : node.path("testsByReport");
      if (reportNode != null && reportNode.isObject()) {
        reportNode.properties().forEach(entry -> reports.put(entry.getKey(), entry.getValue().asInt(-1)));
      }
      return new ReportSummary(node != null && node.path("valid").asBoolean(false),
          node == null ? 0 : node.path("totalTests").asInt(),
          node == null ? 0 : node.path("failures").asInt(),
          node == null ? 0 : node.path("errors").asInt(),
          node == null ? 0 : node.path("skipped").asInt(), reports);
    }

    /** Candidate reports must retain every baseline suite and not reduce its test count. */
    public boolean preserves(ReportSummary baseline) {
      if (baseline == null || !baseline.valid() || !valid || skipped > baseline.skipped) {
        return false;
      }
      return baseline.testsByReport().entrySet().stream()
          .allMatch(entry -> testsByReport.containsKey(entry.getKey())
              && testsByReport.get(entry.getKey()) >= entry.getValue());
    }
  }

  public Map<String, String> details() {
    return Map.of("taskId", TASK_ID, "repository", REPOSITORY,
        "baseRevision", BASE_REVISION, "checkerVersion", CHECKER_VERSION,
        "property", PROPERTY, "environmentOverride", ENVIRONMENT);
  }

  private String probeCommand(String mode) {
    String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
        + "<groupId>org.folio.factory</groupId><artifactId>trusted-config-check</artifactId><version>1</version>"
        + "<properties><maven.compiler.release>21</maven.compiler.release></properties>"
        + "<dependencies><dependency><groupId>io.smallrye.config</groupId><artifactId>smallrye-config-core</artifactId>"
        + "<version>3.17.2</version></dependency></dependencies></project>";
    String source = "import io.smallrye.config.PropertiesConfigSource;"
        + "import io.smallrye.config.SmallRyeConfig;"
        + "import io.smallrye.config.SmallRyeConfigBuilder;"
        + "import java.nio.file.Path;"
        + "public final class ConfigurationProbe {"
        + " public static void main(String[] args) throws Exception {"
        + "  SmallRyeConfig config = new SmallRyeConfigBuilder()"
        + "   .withSources(new PropertiesConfigSource(Path.of(args[0]).toUri().toURL()))"
        + "   .addDefaultSources().addDefaultInterceptors().build();"
        + "  System.out.println(config.getOptionalValue(\"" + PROPERTY
        + "\", Integer.class).map(String::valueOf).orElse(\"MISSING\"));"
        + " }"
        + "}";
    String setup = "rm -rf " + CHECKER_DIR + " && mkdir -p " + CHECKER_DIR + "; "
        + "printf '%s' " + Shell.quote(pom) + " > " + CHECKER_DIR + "/pom.xml; "
        + "printf '%s' " + Shell.quote(source) + " > " + CHECKER_DIR + "/ConfigurationProbe.java; "
        + "mvn -B -ntp -q -f " + CHECKER_DIR
        + "/pom.xml dependency:build-classpath -Dmdep.outputFile=" + CHECKER_DIR
        + "/classpath -Dmdep.includeScope=runtime; "
        + "javac -cp \"$(tr -d '\\n' < " + CHECKER_DIR
        + "/classpath)\" -d " + CHECKER_DIR + " " + CHECKER_DIR + "/ConfigurationProbe.java; ";
    String classpath = CHECKER_DIR + ":$(tr -d '\\n' < " + CHECKER_DIR + "/classpath)";
    if ("BASELINE".equals(mode)) {
      return setup + "printf 'BASELINE='; env -u " + ENVIRONMENT + " java -cp \""
          + classpath + "\" ConfigurationProbe " + CONFIG_PATH
          + " && printf 'OVERRIDE='; env " + ENVIRONMENT + "=12 java -cp \""
          + classpath + "\" ConfigurationProbe " + CONFIG_PATH;
    }
    return setup + "printf 'DEFAULT='; env -u " + ENVIRONMENT + " java -cp \""
        + classpath + "\" ConfigurationProbe " + CONFIG_PATH
        + " && printf 'OVERRIDE='; env " + ENVIRONMENT + "=12 java -cp \""
        + classpath + "\" ConfigurationProbe " + CONFIG_PATH;
  }
}

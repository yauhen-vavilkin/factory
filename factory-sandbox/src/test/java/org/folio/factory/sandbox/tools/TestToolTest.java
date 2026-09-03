package org.folio.factory.sandbox.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TestToolTest {

  private static final SandboxHandle HANDLE = new SandboxHandle("sbx-task1", "c1");
  private static final String ALL_MODULES_COMMAND = "cd repo && mvn test -B";
  private static final String MODULE_COMMAND = "cd repo && mvn -pl 'factory-core' -am test -B";

  @Mock
  private SandboxService sandboxService;

  private TestTool tool;

  @BeforeEach
  void setUp() {
    tool = new TestTool(sandboxService);
  }

  @Test
  void greenRunForModuleProducesSummary() {
    String mavenOutput = """
        [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.5 s -- in org.folio.BarTest
        [INFO]
        [INFO] Results:
        [INFO]
        [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
        [INFO]
        [INFO] BUILD SUCCESS
        """;
    when(sandboxService.exec(HANDLE, MODULE_COMMAND, TestTool.TEST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, mavenOutput, "", 60_000));

    ToolResult result = tool.test(HANDLE, "factory-core");

    assertTrue(result.ok());
    assertTrue(result.output().startsWith("[summary]\nTests run: 3, Failures: 0, Errors: 0, Skipped: 0"));
    assertTrue(result.output().contains("[maven]"));
  }

  @Test
  void sumsPerClassLinesAndReportsFailure() {
    String mavenOutput = """
        [ERROR] Tests run: 4, Failures: 1, Errors: 1, Skipped: 1, Time elapsed: 0.2 s -- in org.folio.BadTest
        [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in org.folio.GoodTest
        [INFO] Results:
        [ERROR] Tests run: 6, Failures: 1, Errors: 1, Skipped: 1
        """;
    when(sandboxService.exec(HANDLE, ALL_MODULES_COMMAND, TestTool.TEST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(1, mavenOutput, "", 60_000));

    ToolResult result = tool.test(HANDLE, null);

    assertFalse(result.ok());
    assertTrue(result.output().contains("Tests run: 6, Failures: 1, Errors: 1, Skipped: 1"));
    assertTrue(result.error().contains("mvn exited with code 1"));
  }

  @Test
  void aggregateOnlyOutputStillParsed() {
    String mavenOutput = """
        [INFO] Results:
        [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
        """;
    when(sandboxService.exec(HANDLE, ALL_MODULES_COMMAND, TestTool.TEST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, mavenOutput, "", 10_000));

    ToolResult result = tool.test(HANDLE, null);

    assertTrue(result.ok());
    assertTrue(result.output().contains("Tests run: 5, Failures: 0, Errors: 0, Skipped: 0"));
  }

  @Test
  void failingTestsMarkResultFailedEvenIfMavenExitZero() {
    String mavenOutput = """
        [INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.2 s -- in org.folio.FlakyTest
        """;
    when(sandboxService.exec(HANDLE, ALL_MODULES_COMMAND, TestTool.TEST_TIMEOUT_SEC))
        .thenReturn(new CommandResult(0, mavenOutput, "", 10_000));

    ToolResult result = tool.test(HANDLE, null);

    assertFalse(result.ok());
    assertTrue(result.error().contains("tests failed"));
  }

  @Test
  void parseSumsAndPrefersPerClassLinesOverAggregate() {
    TestTool.SurefireSummary summary = TestTool.parse("""
        [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in org.folio.ATest
        [INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 -- in org.folio.BTest
        [INFO] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
        """);


    assertEquals(5, summary.tests());
    assertEquals(1, summary.failures());
    assertTrue(summary.found());
    assertFalse(summary.allPassed());
  }

  @Test
  void parseWithoutMatchesReturnsNotFound() {
    TestTool.SurefireSummary summary = TestTool.parse("[INFO] BUILD SUCCESS");

    assertFalse(summary.found());
    assertEquals("no surefire results parsed", summary.text());
  }
}

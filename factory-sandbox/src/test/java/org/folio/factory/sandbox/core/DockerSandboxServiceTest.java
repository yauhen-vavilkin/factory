package org.folio.factory.sandbox.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DockerSandboxServiceTest {

  private static final String IMAGE = "maven:3.9-eclipse-temurin-21";
  private static final String CONTAINER_ID = "c1";
  private static final String EXEC_ID = "e1";
  private static final SandboxSpec SPEC =
      new SandboxSpec("task1", "https://example.com/repo.git", "main", "feature/x");
  private static final String CLONE_COMMAND =
      "git clone --depth 1 https://example.com/repo.git repo && cd repo && git checkout -b feature/x main";

  @Mock
  private DockerClient dockerClient;

  private ExecCreateCmd execCreateCmd;

  private DockerSandboxService service;

  @BeforeEach
  void setUp() {
    service = new DockerSandboxService(dockerClient);
  }

  @Test
  void createHappyPath() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    mockExecPipeline(CLONE_COMMAND, 0, "clone ok".getBytes(UTF_8), new byte[0]);

    SandboxHandle handle = service.create(SPEC);

    assertEquals("sbx-task1", handle.sandboxId());
    assertEquals(CONTAINER_ID, handle.containerId());
    verify(dockerClient).createContainerCmd(IMAGE);
    verify(createCmd).withWorkingDir("/workspace");
    verify(dockerClient).startContainerCmd(CONTAINER_ID);
    verify(dockerClient).execCreateCmd(CONTAINER_ID);
    verify(execCreateCmd).withCmd("/bin/sh", "-c", CLONE_COMMAND);
  }

  @Test
  void cloneFailsThrowsSandboxException() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    mockExecPipeline(CLONE_COMMAND, 128, new byte[0], "fatal: repository not found".getBytes(UTF_8));

    SandboxException ex = assertThrows(SandboxException.class, () -> service.create(SPEC));

    assertTrue(ex.getMessage().contains("fatal: repository not found"));
  }

  @Test
  void execCollectsOutput() throws Exception {
    mockExecPipeline("mvn test", 0, "hello".getBytes(UTF_8), "warn".getBytes(UTF_8));

    CommandResult result = service.exec(new SandboxHandle("sbx-task1", CONTAINER_ID), "mvn test", 60L);

    assertEquals(0, result.exitCode());
    assertEquals("hello", result.stdout());
    assertEquals("warn", result.stderr());
    assertTrue(result.durationMs() >= 0);
    assertTrue(result.ok());
    verify(execCreateCmd).withCmd("/bin/sh", "-c", "mvn test");
  }

  @Test
  void teardownRemovesContainer() {
    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);

    SandboxHandle handle = new SandboxHandle("sbx-task1", CONTAINER_ID);
    service.teardown(handle);
    service.teardown(handle);

    verify(dockerClient, times(2)).removeContainerCmd(CONTAINER_ID);
    verify(removeCmd, times(2)).withForce(true);
    verify(removeCmd, times(2)).withRemoveVolumes(true);
    verify(removeCmd, times(2)).exec();
  }

  private void mockExecPipeline(String command, int exitCode, byte[] stdout, byte[] stderr) throws Exception {
    execCreateCmd = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
    when(execCreateResponse.getId()).thenReturn(EXEC_ID);
    when(dockerClient.execCreateCmd(CONTAINER_ID)).thenReturn(execCreateCmd);
    when(execCreateCmd.exec()).thenReturn(execCreateResponse);

    ExecStartCmd execStartCmd = mock(ExecStartCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.execStartCmd(EXEC_ID)).thenReturn(execStartCmd);
    when(execStartCmd.exec(any(ExecStartResultCallback.class))).thenAnswer(invocation -> {
      ExecStartResultCallback callback = invocation.getArgument(0);
      callback.onNext(new Frame(StreamType.STDOUT, stdout));
      callback.onNext(new Frame(StreamType.STDERR, stderr));
      callback.onComplete();
      return callback;
    });

    InspectExecCmd inspectExecCmd = mock(InspectExecCmd.class);
    InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
    when(inspectResponse.getExitCode()).thenReturn(exitCode);
    when(dockerClient.inspectExecCmd(EXEC_ID)).thenReturn(inspectExecCmd);
    when(inspectExecCmd.exec()).thenReturn(inspectResponse);
  }
}

package org.folio.factory.sandbox.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import java.nio.file.Path;
import java.time.Duration;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import org.folio.factory.sandbox.api.CommandResult;
import org.folio.factory.sandbox.api.SandboxHandle;
import org.folio.factory.sandbox.api.SandboxSpec;
import org.folio.factory.sandbox.exception.SandboxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(MockitoExtension.class)
class DockerSandboxServiceTest {

  private static final String IMAGE = "factory-pi:jdk21";
  private static final String CONTAINER_ID = "c1";
  private static final String EXEC_ID = "e1";
  private static final SandboxSpec SPEC =
      new SandboxSpec("task1", "https://example.com/repo.git", "main", "feature/x");
  private static final String CLONE_COMMAND =
      "cp /opt/pi/models.json /state/pi/models.json && git clone --depth 1 'https://example.com/repo.git' repo && cd repo && git checkout -b 'feature/x' 'main'";

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
  void createSharesOnlyThePersistentMavenRepositoryAndHasNoCpuOrMemoryCaps() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    mockExecPipeline(CLONE_COMMAND, 0, new byte[0], new byte[0]);

    service.create(SPEC);

    ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfigCaptor.capture());
    HostConfig config = hostConfigCaptor.getValue();
    assertEquals(1, config.getBinds().length);
    assertEquals("/maven-repository", config.getBinds()[0].getVolume().getPath());
    assertEquals(com.github.dockerjava.api.model.AccessMode.rw, config.getBinds()[0].getAccessMode());
    assertTrue(config.getNanoCPUs() == null || config.getNanoCPUs() == 0L, "no CPU cap");
    assertTrue(config.getMemory() == null || config.getMemory() == 0L, "no memory cap");
    verify(createCmd).withEnv("MAVEN_ARGS=-Dmaven.repo.local=/maven-repository");
    assertEquals(512L, config.getPidsLimit());
    assertTrue(config.getReadonlyRootfs());
    assertTrue(config.getTmpFs().containsKey("/tmp"));
    assertTrue(!config.getTmpFs().containsKey("/workspace"),
        "candidate files must survive workload stop");
    verify(createCmd).withVolumes(new com.github.dockerjava.api.model.Volume("/workspace"));
  }

  @Test
  void createProvidesWritablePiStateRootForCredentialStore() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);
    when(dockerClient.startContainerCmd(CONTAINER_ID))
        .thenReturn(mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF)));
    mockExecPipeline(CLONE_COMMAND, 0, new byte[0], new byte[0]);

    service.create(SPEC);

    ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfig.capture());
    String piStateMount = hostConfig.getValue().getTmpFs().get("/state/pi");
    assertTrue(piStateMount != null && piStateMount.startsWith("rw,"),
        "Pi credential/state root must be writable");
  }

  @Test
  void gatewayOnlyWithoutNetworkFailsBeforeContainerCreation() {
    SandboxSpec gatewaySpec = new SandboxSpec("task1", "https://example.com/repo.git", "main",
        "feature/x", "owner", IMAGE, "linux/amd64", "GATEWAY_ONLY");
    SandboxException error = assertThrows(SandboxException.class, () -> service.create(gatewaySpec));
    assertTrue(error.getMessage().contains("GATEWAY_ONLY requires"));
    verify(dockerClient, times(0)).createContainerCmd(any());
  }

  @Test
  void productionCreateUsesConfiguredGatewayNetwork() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);
    when(dockerClient.startContainerCmd(CONTAINER_ID))
        .thenReturn(mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF)));
    mockExecPipeline(CLONE_COMMAND, 0, new byte[0], new byte[0]);

    service = new DockerSandboxService(dockerClient, new SandboxProperties("docker", IMAGE,
        "docker", null, Duration.ZERO, null, "factory-pi-network"));
    service.create(SPEC);

    ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfig.capture());
    assertEquals("factory-pi-network", hostConfig.getValue().getNetworkMode());
  }

  @Test
  void nonePolicyUsesDockerNoneNetwork(@TempDir Path source) throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);
    when(dockerClient.startContainerCmd(CONTAINER_ID))
        .thenReturn(mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF)));
    mockExecPipeline("clone", 0, new byte[0], new byte[0]);

    SandboxSpec noNetwork = new SandboxSpec("task1", source.toUri().toString(), "main", "feature/x",
        "owner", IMAGE, "linux/amd64", "NONE");
    service.create(noNetwork);

    ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
    verify(createCmd).withHostConfig(hostConfig.capture());
    assertEquals("none", hostConfig.getValue().getNetworkMode());
  }

  @Test
  void unknownNetworkPolicyFailsClosed() {
    SandboxSpec unknown = new SandboxSpec("task1", "https://example.com/repo.git", "main",
        "feature/x", "owner", IMAGE, "linux/amd64", "DIRECT");
    SandboxException error = assertThrows(SandboxException.class, () -> service.create(unknown));
    assertTrue(error.getMessage().contains("unsupported sandbox network policy"));
    verify(dockerClient, times(0)).createContainerCmd(any());
  }

  @Test
  void timedOutExecKillsSandboxWorkload() throws Exception {
    execCreateCmd = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    ExecCreateCmdResponse execCreateResponse = mock(ExecCreateCmdResponse.class);
    when(execCreateResponse.getId()).thenReturn(EXEC_ID);
    when(dockerClient.execCreateCmd(CONTAINER_ID)).thenReturn(execCreateCmd);
    when(execCreateCmd.exec()).thenReturn(execCreateResponse);

    ExecStartCmd execStartCmd = mock(ExecStartCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.execStartCmd(EXEC_ID)).thenReturn(execStartCmd);
    when(execStartCmd.exec(any(ExecStartResultCallback.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    KillContainerCmd kill = mock(KillContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.killContainerCmd(CONTAINER_ID)).thenReturn(kill);

    CommandResult result = service.exec(new SandboxHandle("sbx-task1", CONTAINER_ID), "sleep infinity", 0);

    assertEquals(-1, result.exitCode());
    verify(dockerClient).killContainerCmd(CONTAINER_ID);
    verify(kill).exec();
  }

  @Test
  void createRunsKeepAliveCommandInsteadOfImageDefault() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    mockExecPipeline(CLONE_COMMAND, 0, new byte[0], new byte[0]);

    service.create(SPEC);

    verify(createCmd).withCmd("sleep", "infinity");
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
    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);

    SandboxException ex = assertThrows(SandboxException.class, () -> service.create(SPEC));

    assertTrue(ex.getMessage().contains("fatal: repository not found"));
    verify(dockerClient).removeContainerCmd(CONTAINER_ID);
    verify(removeCmd).withForce(true);
    verify(removeCmd).exec();
  }

  @Test
  void cloneThrowStillRemovesContainer() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    execCreateCmd = mock(ExecCreateCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.execCreateCmd(CONTAINER_ID)).thenReturn(execCreateCmd);
    when(execCreateCmd.exec()).thenThrow(new IllegalStateException("docker exec pipeline blew up"));

    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);

    assertThrows(SandboxException.class, () -> service.create(SPEC));

    verify(dockerClient).removeContainerCmd(CONTAINER_ID);
    verify(removeCmd).withForce(true);
    verify(removeCmd).exec();
  }

  @Test
  void cleanupFailureDoesNotMaskOriginalException() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    mockExecPipeline(CLONE_COMMAND, 128, new byte[0], "fatal: repository not found".getBytes(UTF_8));
    RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.removeContainerCmd(CONTAINER_ID)).thenReturn(removeCmd);
    when(removeCmd.exec()).thenThrow(new IllegalStateException("remove container failed"));

    SandboxException ex = assertThrows(SandboxException.class, () -> service.create(SPEC));

    assertTrue(ex.getMessage().contains("fatal: repository not found"));
  }

  @Test
  void createQuotesCloneCommandTokens() throws Exception {
    CreateContainerCmd createCmd = mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
    when(createResponse.getId()).thenReturn(CONTAINER_ID);
    when(dockerClient.createContainerCmd(IMAGE)).thenReturn(createCmd);
    when(createCmd.exec()).thenReturn(createResponse);

    StartContainerCmd startCmd = mock(StartContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
    when(dockerClient.startContainerCmd(CONTAINER_ID)).thenReturn(startCmd);

    mockExecPipeline("clone", 0, new byte[0], new byte[0]);

    service.create(SPEC);
    service.create(new SandboxSpec("task1", "https://example.com/repo.git", "main", "feature/x; touch /pwned"));

    ArgumentCaptor<String> cmdCaptor = ArgumentCaptor.forClass(String.class);
    verify(execCreateCmd, times(2)).withCmd(eq("/bin/sh"), eq("-c"), cmdCaptor.capture());
    assertEquals("cp /opt/pi/models.json /state/pi/models.json && git clone --depth 1 'https://example.com/repo.git' repo && cd repo && git checkout -b "
        + "'feature/x' 'main'", cmdCaptor.getAllValues().get(0));
    assertEquals("cp /opt/pi/models.json /state/pi/models.json && git clone --depth 1 'https://example.com/repo.git' repo && cd repo && git checkout -b "
        + "'feature/x; touch /pwned' 'main'", cmdCaptor.getAllValues().get(1));
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
    verify(execCreateCmd).withWorkingDir("/workspace");
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

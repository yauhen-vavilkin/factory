package org.folio.factory.devfactory.feedback;

import org.folio.factory.core.agent.*;
import org.folio.factory.core.domain.*;
import org.folio.factory.core.service.*;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.devfactory.*;
import org.folio.factory.devfactory.candidate.*;
import org.folio.factory.devfactory.delivery.*;
import org.folio.factory.devfactory.runtime.*;
import org.folio.factory.devfactory.verification.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.folio.factory.devfactory.feedback.FeedbackArtifacts.*;

@EnabledIfEnvironmentVariable(named="FACTORY_DOCKER_TEST",matches="true")
class FeedbackDockerTest {
    @TempDir Path root;
    @Test void realContainersExportVerifyPublishAndLeaveNoResourcesWhileWaiting() throws Exception {
        var id=UUID.randomUUID(); String volume="factory-pr-test-"+id;
        Path bare=root.resolve("fork/repo.git"), work=root.resolve("work");
        CandidateFreezer.git(root,"init","--bare",bare.toString()); CandidateFreezer.git(root,"init","-b","feedback",work.toString());
        Files.writeString(work.resolve("value.txt"),"initial\n"); CandidateFreezer.git(work,"add",".");
        CandidateFreezer.git(work,"-c","user.name=Fixture","-c","user.email=fixture@example.org","commit","-m","initial");
        CandidateFreezer.git(work,"push",bare.toString(),"feedback");
        String base=CandidateFreezer.git(work,"rev-parse","HEAD").strip();
        var repositories=new DevFactoryProperties(root.toString(),new TreeMap<>(Map.of("fixture",new DevFactoryProperties.Repository("upstream/repo","main","alpine:3.21","checks",List.of("TEST"),List.of()))));
        var delivery=new DevDeliveryProperties(Map.of("fixture",new DeliveryTarget("fork/repo","main",true)),true);
        var command=List.of("/bin/sh","-c","test \"$(cat value.txt)\" = corrected && mkdir -p target/surefire-reports && printf '%s' '<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"behavior\"/></testsuite>' > target/surefire-reports/TEST-fixture.xml");
        var runtime=new DevRuntimeProperties(Map.of("checks",command),null,60,volume);
        var docker=new DockerWorkloads(); var freezer=new CandidateFreezer();
        var gateway=mock(PrFeedbackGateway.class);
        when(gateway.read("fork/repo",7)).thenAnswer(i->new PrFeedbackGateway.PullRequest("fork/repo",7,"feedback","main",CandidateFreezer.git(bare,"rev-parse","feedback").strip(),"https://github.com/fork/repo/pull/7",true));
        when(gateway.status(any())).thenReturn(PrFeedbackGateway.Status.OPEN);
        var comment=new PrFeedbackGateway.Comment("inline:1","reviewer","Correct value","value.txt",1,false,"now","url");
        when(gateway.comments(any())).thenAnswer(i->new PrFeedbackGateway.Snapshot("snapshot",gateway.read("fork/repo",7),List.of(comment)));
        var coding=new CodingRuntime() {
            public String id(){return "scripted-test";} public String image(){return "alpine:3.21";} public void requireConfigured(){}
            public CodingOutcome code(DockerWorkloads.Workload workload,CodingRequest request,int timeout) {
                workload.execute(List.of("/bin/sh","-c","test ! -S /var/run/docker.sock && printf 'corrected\\n' > value.txt"),timeout).requireSuccess();
                return CodingOutcome.completed("Fixture correction",Map.of());
            }
        };
        var state=mock(StateManager.class);when(state.get(id)).thenReturn(new PipelineExecution("dev-factory",VERSION,null));
        var audit=mock(AuditLog.class);when(audit.forExecution(id)).thenReturn(List.of());
        var worker=new FeedbackWorker(repositories,delivery,runtime,new GitHubProperties("",""),gateway,freezer,
                new CandidateVerifier(repositories,runtime,freezer,docker),coding,docker,new FeedbackGit(freezer),state,audit,model->Map.of());
        var request=new CodingRequest("TEST-1","Correct value","Original task",List.of(),List.of(),List.of(),List.of(),new CodingRequest.RepositoryTarget("fixture","upstream/repo","main",base),null);
        var saved=new LinkedHashMap<>(initial());
        saved.put("dev_coding_request.json",JSON.writeValueAsString(request));
        saved.put("dev_delivery.json",JSON.writeValueAsString(Map.of("state","DELIVERED","repository","fixture","deliveryBranch","feedback","pullRequestUrl","https://github.com/fork/repo/pull/7")));
        try {
            var initialized=worker.execute(context(id,saved)); saved.putAll(initialized.outputs());
            assertThat(initialized.disposition()).isEqualTo(AgentResult.Disposition.WAIT);
            var s=JSON.readValue(saved.get(STATE),FeedbackState.class);s.round=1;s.attemptId=UUID.randomUUID().toString();s.startedAt=Instant.now().toString();s.selected=List.of(comment);s.snapshotId="snapshot";s.phase=FeedbackState.Phase.PREPARE;
            saved.put(STATE,JSON.writeValueAsString(s));
            for(int i=0;i<6;i++) {
                var result=worker.execute(context(id,saved));saved.putAll(result.outputs());
                if(result.disposition()==AgentResult.Disposition.WAIT)break;
            }
            var result=JSON.readValue(saved.get(STATE),FeedbackState.class);
            assertThat(result.phase).as(result.reason).isEqualTo(FeedbackState.Phase.READY);
            assertThat(result.verification.result()).isEqualTo("PASS");assertThat(result.verification.testCount()).isEqualTo(1);
            assertThat(CandidateFreezer.git(bare,"rev-parse","feedback").strip()).isEqualTo(result.commitSha).isNotEqualTo(base);
            assertThat(Processes.run(null,List.of("docker","ps","-aq","--filter","label=factory.dev-execution="+id),30).requireSuccess().output()).isBlank();
        } finally {
            // Explicit test-owned volume; never touches the configured application cache.
            Processes.run(null,List.of("docker","volume","rm",volume),30);
        }
    }
    AgentContext context(UUID id,Map<String,String> saved) {
        var inputs=new LinkedHashMap<String,ArtifactContent>();saved.forEach((k,v)->inputs.put(k,new ArtifactContent(k,1,"text/markdown",v)));
        return new AgentContext(id,STEP,inputs,null,Map.of(),OUTPUTS,new AgentContext.Invocation(UUID.randomUUID(),false,()->{},d->{}));
    }
}

package org.folio.factory.app;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.folio.factory.connectors.github.GitHubProperties;
import org.folio.factory.devfactory.feedback.*;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
class PrFeedbackGatewayTest {
    WireMockServer server;
    GitHubPrFeedbackGateway gateway;
    String sha="a".repeat(40);
    @BeforeEach void start() {
        server=new WireMockServer(0);server.start();gateway=new GitHubPrFeedbackGateway(new GitHubProperties(server.baseUrl(),"fixture-token"),RestClient.builder());
        stub("/repos/fork/repo/pulls/7",pr("fork/repo","open",false));
    }
    @AfterEach void stop() {server.stop();}
    @Test void paginatesAllThreeCommentSourcesAndInvalidatesEditedSnapshot() {
        stub("/repos/fork/repo/issues/7/comments?per_page=100&page=1","["+IntStream.range(1,101).mapToObj(i->comment(i,"discussion")).collect(java.util.stream.Collectors.joining(","))+"]");
        stub("/repos/fork/repo/issues/7/comments?per_page=100&page=2","["+comment(101,"last discussion")+"]");
        stub("/repos/fork/repo/pulls/7/reviews?per_page=100&page=1","["+comment(1,"review")+"]");
        stub("/repos/fork/repo/pulls/7/comments?per_page=100&page=1","[{\"id\":1,\"body\":\"inline\",\"path\":\"src/Test.java\",\"line\":3,\"position\":null}]");
        var pr=gateway.read("fork/repo",7);var snapshot=gateway.comments(pr);
        assertThat(snapshot.comments()).hasSize(103).anyMatch(c->c.id().equals("inline:1")&&c.outdated()&&c.line()==3);
        assertThat(gateway.comments(pr).id()).isEqualTo(snapshot.id());
        stub("/repos/fork/repo/pulls/7/reviews?per_page=100&page=1","["+comment(1,"edited review")+"]");
        assertThat(gateway.comments(pr).id()).isNotEqualTo(snapshot.id());
        server.verify(0,postRequestedFor(anyUrl()));
    }
    @Test void rejectsDifferentForkAndIdentifiesClosedOrMergedPr() {
        stub("/repos/fork/repo/pulls/7",pr("other/repo","open",false));
        assertThatThrownBy(()->gateway.read("fork/repo",7)).hasMessageContaining("authorized delivery target");
        var saved=new PrFeedbackGateway.PullRequest("fork/repo",7,"feedback","main",sha,"",true);
        assertThatThrownBy(()->gateway.status(saved)).hasMessageContaining("branch identity changed");
        stub("/repos/fork/repo/pulls/7",pr("fork/repo","closed",true));
        assertThat(gateway.read("fork/repo",7).open()).isFalse();
        assertThat(gateway.status(saved)).isEqualTo(PrFeedbackGateway.Status.MERGED);
        stub("/repos/fork/repo/pulls/7",pr("fork/repo","closed",false));
        assertThat(gateway.status(saved)).isEqualTo(PrFeedbackGateway.Status.CLOSED);
        stub("/repos/fork/repo/pulls/7",pr("fork/repo","open",false));
        assertThat(gateway.status(saved)).isEqualTo(PrFeedbackGateway.Status.OPEN);
        stub("/repos/fork/repo/pulls/7",pr("fork/repo","closed",true).replace("\"ref\":\"main\"","\"ref\":\"other\""));
        assertThatThrownBy(()->gateway.status(saved)).hasMessageContaining("destination");
    }
    String comment(int id,String body) {return "{\"id\":"+id+",\"body\":\""+body+"\",\"user\":{\"login\":\"reviewer\"}}";}
    String pr(String headRepo,String status,boolean merged) {return "{\"state\":\""+status+"\",\"merged\":"+merged+",\"head\":{\"ref\":\"feedback\",\"sha\":\""+sha+"\",\"repo\":{\"full_name\":\""+headRepo+"\"}},\"base\":{\"ref\":\"main\",\"repo\":{\"full_name\":\"fork/repo\"}}}";}
    void stub(String url,String body) {server.stubFor(get(urlEqualTo(url)).willReturn(okJson(body)));}
}

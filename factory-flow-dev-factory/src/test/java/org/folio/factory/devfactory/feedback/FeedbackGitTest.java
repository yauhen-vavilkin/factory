package org.folio.factory.devfactory.feedback;

import org.folio.factory.devfactory.candidate.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class FeedbackGitTest {
    @TempDir Path root;
    CandidateFreezer freezer=new CandidateFreezer();
    FeedbackGit publisher=new FeedbackGit(freezer);
    Path bare, work;
    String base;
    @BeforeEach void setup() throws Exception {
        bare=root.resolve("fork.git"); work=root.resolve("work");
        git(root,"init","--bare",bare.toString()); git(root,"init","-b","feedback",work.toString());
        Files.writeString(work.resolve("value.txt"),"initial\n"); commit("initial");
        git(work,"push",bare.toString(),"feedback"); base=git(work,"rev-parse","HEAD").strip();
    }
    Candidate candidate() throws Exception {
        Files.writeString(work.resolve("value.txt"),"corrected\n");
        return freezer.freeze("fixture",bare.toString(),base,work,true);
    }
    FeedbackGit.Intent intent(Candidate c) { return publisher.prepare(bare.toString(),c,"Review correction","2026-09-23T12:00:00Z"); }
    String publish(Candidate c, FeedbackGit.Intent i) { return publisher.publish(bare.toString(),"feedback",c,i,"",()->{}); }
    @Test void deterministicCommitReconcilesLostAcknowledgmentAndLaterManualCommits() throws Exception {
        var c=candidate(); var i=intent(c);
        assertThat(intent(c)).isEqualTo(i);
        assertThat(publish(c,i)).isEqualTo(i.sha());
        assertThat(publish(c,i)).isEqualTo(i.sha());
        git(work,"reset","--hard",base); git(work,"fetch",bare.toString(),"feedback"); git(work,"reset","--hard","FETCH_HEAD");
        Files.writeString(work.resolve("manual.txt"),"manual\n"); commit("manual"); git(work,"push",bare.toString(),"feedback");
        String manual=git(work,"rev-parse","HEAD").strip();
        assertThat(publish(c,i)).isEqualTo(i.sha());
        assertThat(git(bare,"rev-parse","feedback").strip()).isEqualTo(manual);
        assertThat(git(bare,"rev-list","--count","feedback").strip()).isEqualTo("3");
    }
    @Test void divergentBranchAndDeletedBranchAreNeverOverwritten() throws Exception {
        var c=candidate(); var i=intent(c);
        git(work,"reset","--hard",base); Files.writeString(work.resolve("manual.txt"),"manual\n"); commit("different change");
        git(work,"push",bare.toString(),"feedback"); String changed=git(bare,"rev-parse","feedback").strip();
        assertThatThrownBy(()->publish(c,i)).hasMessageContaining("branch changed");
        assertThat(git(bare,"rev-parse","feedback").strip()).isEqualTo(changed);
        git(bare,"update-ref","-d","refs/heads/feedback");
        assertThatThrownBy(()->publish(c,i)).hasMessageContaining("fetch failed");
    }
    @Test void ownershipLossDoesNotPushAndUnchangedTreesRoundTrip() throws Exception {
        var unchanged=freezer.freeze("fixture",bare.toString(),base,work,true);
        assertThat(unchanged.patch()).isEmpty();
        Path rebuilt=freezer.reconstruct(bare.toString(),unchanged); CandidateFreezer.cleanup(rebuilt);
        var c=candidate(); var i=intent(c);
        assertThatThrownBy(()->publisher.publish(bare.toString(),"feedback",c,i,"",()->{throw new IllegalStateException("lost lease");}))
                .hasMessage("lost lease");
        assertThat(git(bare,"rev-parse","feedback").strip()).isEqualTo(base);
    }
    void commit(String message) { git(work,"add","."); git(work,"-c","user.name=Fixture","-c","user.email=fixture@example.org","commit","-m",message); }
    static String git(Path p,String...args) { return CandidateFreezer.git(p,args); }
}

package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodingProtocolTest {
    private static CodingRequest request() {
        return new CodingRequest("TASK-1", "Summary", "Description", List.of(), List.of(), List.of(), List.of(),
                new CodingRequest.RepositoryTarget("repo", "owner/repo", "main", "a".repeat(40)),
                CodingRequest.Constraints.defaults());
    }

    @Test
    void promptNamesTheVerificationCommandAndThePreparedWorkspace() {
        String prompt = CodingProtocol.prompt(request().withVerification("yarn test --maxWorkers=2", true));

        assertThat(prompt)
                .contains("Factory independently verifies the frozen candidate by running: yarn test --maxWorkers=2")
                .contains("already installed in /workspace")
                .contains("Run that command, or the relevant part of it, before you finish.");
    }

    @Test
    void promptTellsTheAgentToKeepLongCheckOutputOutOfTheSession() {
        String prompt = CodingProtocol.prompt(request().withVerification("yarn test --maxWorkers=2", true));

        assertThat(prompt).contains("a session has a hard size limit")
                .contains("(yarn test --maxWorkers=2) > /tmp/factory-check.log 2>&1; echo \"exit=$?\"; tail -n 80 /tmp/factory-check.log");
        assertThat(CodingProtocol.prompt(request())).doesNotContain("factory-check.log");
    }

    @Test
    void promptOmitsTheInstallLineWhenNothingWasInstalled() {
        String prompt = CodingProtocol.prompt(request().withVerification("mvn -B -ntp verify", false));

        assertThat(prompt).contains("by running: mvn -B -ntp verify").doesNotContain("already installed");
    }

    @Test
    void promptWithoutACommandIsUnchanged() {
        assertThat(CodingProtocol.prompt(request())).doesNotContain("independently verifies the frozen candidate by running")
                .contains("independently verifies the frozen candidate.\n\nFor NEEDS_DECISION");
    }

    @Test
    void requestsStoredBeforeThisFieldExistedStillParse() throws Exception {
        var json = JsonMapper.builder().build();
        String stored = json.writeValueAsString(request());
        String legacy = stored.replaceAll(",\"verificationCommand\":\"[^\"]*\",\"dependenciesInstalled\":(true|false)", "");

        var parsed = json.readValue(legacy, CodingRequest.class);

        assertThat(legacy).doesNotContain("verificationCommand");
        assertThat(parsed.constraints().verificationCommand()).isEmpty();
        assertThat(parsed.constraints().dependenciesInstalled()).isFalse();
    }

    @Test
    void withVerificationKeepsEveryOtherConstraint() {
        var changed = request().withVerification("yarn test", true).constraints();

        assertThat(changed.workingDirectory()).isEqualTo("/workspace");
        assertThat(changed.independentVerification()).isTrue();
        assertThat(changed.allowPush()).isFalse();
        assertThat(changed.verificationCommand()).isEqualTo("yarn test");
        assertThat(changed.dependenciesInstalled()).isTrue();
    }
}

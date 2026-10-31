package org.folio.factory.devfactory.delivery;


import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Git environment for publication: commit identity and a push credential that never reaches argv. */
public final class PublicationGit {

    private PublicationGit() {}

    public static Map<String, String> identity(String date, String name, String email) {
        return Map.of(
                "GIT_AUTHOR_NAME",
                name,
                "GIT_AUTHOR_EMAIL",
                email,
                "GIT_COMMITTER_NAME",
                name,
                "GIT_COMMITTER_EMAIL",
                email,
                "GIT_AUTHOR_DATE",
                date,
                "GIT_COMMITTER_DATE",
                date);
    }

    public static Map<String, String> authentication(String scope, String token) {
        String header =
                "Authorization: Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + token)
                                                .getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "GIT_CONFIG_COUNT",
                "1",
                "GIT_CONFIG_KEY_0",
                "http." + scope + ".extraHeader",
                "GIT_CONFIG_VALUE_0",
                header);
    }
}

package org.folio.factory.devfactory.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The coding sandbox sees only a generic OpenAI-compatible provider, the
 * Factory gateway, and the stable model alias. The upstream vendor, model, and
 * URL are trusted host/gateway configuration and never appear in the public
 * example configuration.
 */
class ProviderNeutralModelConfigTest {
  private static final Path ROOT = Path.of("..");
  private static final Pattern VENDOR = Pattern.compile("(?i)z\\.ai|zai|glm|bigmodel|zhipu");
  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void piProfileRequestsTheGenericProviderAndAlias() {
    ExecutionProfile pi = new TrustedProfileCatalog()
        .profile(TrustedProfileCatalog.JAVA_MAVEN_PI).orElseThrow();

    assertThat(pi.modelProvider()).isEqualTo("openai-compatible");
    assertThat(pi.modelId()).isEqualTo("factory-coding");
  }

  @Test
  void piModelsDeclareOnlyTheGatewayAliasWithoutUpstreamDetails() throws Exception {
    JsonNode providers = json.readTree(Files.readString(ROOT.resolve("factory-sandbox/pi-models.json")))
        .path("providers");
    assertThat(providers.propertyNames()).containsExactly(TrustedProfileCatalog.MODEL_PROVIDER);

    JsonNode provider = providers.path(TrustedProfileCatalog.MODEL_PROVIDER);
    assertThat(provider.path("api").asString()).isEqualTo("openai-completions");
    assertThat(provider.path("baseUrl").asString()).isEqualTo("http://factory-gateway:8080/v1");
    assertThat(provider.path("apiKey").asString()).isEqualTo("$FACTORY_MODEL_TOKEN");
    assertThat(provider.path("models")).hasSize(1);
    assertThat(provider.path("models").get(0).path("id").asString())
        .isEqualTo(TrustedProfileCatalog.MODEL_ALIAS);
  }

  @Test
  void publicExampleConfigurationNamesNoVendorProviderOrModel() throws Exception {
    for (String file : List.of(".env.example", "docker-compose.yml",
        "factory-app/src/main/resources/application.yaml")) {
      assertThat(Files.readString(ROOT.resolve(file))).as(file).doesNotContainPattern(VENDOR);
    }
    assertThat(Files.readString(ROOT.resolve(".env.example")))
        .contains("FACTORY_MODEL_PROVIDER=openai-compatible")
        .contains("FACTORY_MODEL_ALIAS=factory-coding");

    // Pi's wire-dialect compat values (thinkingFormat, zaiToolStream) are Pi
    // enum/flag names for the request format, not Factory identifiers; they
    // are the only vendor-shaped tokens the sandbox configuration may carry.
    ObjectNode piModels = (ObjectNode) json.readTree(
        Files.readString(ROOT.resolve("factory-sandbox/pi-models.json")));
    for (JsonNode model : piModels.path("providers").path(TrustedProfileCatalog.MODEL_PROVIDER)
        .path("models")) {
      ((ObjectNode) model.path("compat")).remove(List.of("thinkingFormat", "zaiToolStream"));
    }
    assertThat(piModels.toString()).doesNotContainPattern(VENDOR);
  }
}

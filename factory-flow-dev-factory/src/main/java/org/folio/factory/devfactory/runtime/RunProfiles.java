package org.folio.factory.devfactory.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The Claude Code run profiles a person chooses from when starting a run. Profile env-default is built
 * from the FACTORY_CLAUDE_CODE_* settings, so an old .env keeps working; the other profiles come from
 * config/developer-run-profiles.yaml. A profile file names the variable that holds its secret, never
 * the secret itself.
 */
public final class RunProfiles {
    public static final String ENV_DEFAULT = "env-default";
    public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    public static final int MAX_TURNS = 500;
    public static final int MAX_INSTRUCTIONS = 8000;
    private static final String FILE = "config/developer-run-profiles.yaml";
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");

    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private final List<String> secrets = new ArrayList<>();
    private final String defaultId;

    /** A run cannot use its profile. The message is safe to show: it names the profile and what to fix. */
    public static final class ProfileException extends IllegalStateException {
        public ProfileException(String message) {
            super(message);
        }
    }

    @ConfigurationProperties(prefix = "factory.dev-factory")
    public record Properties(List<FileProfile> runProfiles, String defaultRunProfile) {
    }

    /** One entry of config/developer-run-profiles.yaml. */
    public record FileProfile(String id, String label, String baseUrl, String authentication, String secretEnv,
                              String model, List<String> efforts, String defaultEffort, Integer maxTurns,
                              Integer maxOutputTokens) {
    }

    /**
     * A profile ready to use. defaultEffort null means "send no --effort" (the CLI then uses high).
     * problem is why the profile cannot run, or null.
     */
    public record Profile(String id, String label, String baseUrl, String authentication, String secretVariable,
                          String secret, String model, List<String> efforts, String defaultEffort, int maxTurns,
                          int maxOutputTokens, String invalid) {
        public String problem() {
            if (secret == null || secret.isBlank()) {
                // Compose passes only the variables it lists to Factory, so a new secret-env must be listed too.
                return "Profile " + id + " is not configured: set " + secretVariable + " in .env"
                        + (ENV_DEFAULT.equals(id) ? ", then run" : ". If docker-compose.yml does not pass "
                                + secretVariable + " to the factory service yet, add it there. Then run")
                        + " docker compose up -d again.";
            }
            return invalid;
        }

        public boolean configured() {
            return problem() == null;
        }

        public String endpointOrigin() {
            return origin(baseUrl);
        }

        @Override
        public String toString() {
            return "Profile[id=" + id + ", model=" + model + "]";
        }
    }

    /** What one run uses. effort null means the CLI default; instructions may be null. */
    public record Run(Profile profile, String effort, int maxTurns, String instructions) {
    }

    public RunProfiles(DevRuntimeProperties.ClaudeCode environment, Properties file,
                       Function<String, String> variables) {
        Profile envDefault = envDefault(environment);
        profiles.put(envDefault.id(), envDefault);
        // Both legacy credentials are redacted, whichever one the authentication setting selects.
        addSecret(environment.apiKey());
        addSecret(environment.authToken());
        List<FileProfile> entries = file == null || file.runProfiles() == null ? List.of() : file.runProfiles();
        for (FileProfile entry : entries) {
            Profile profile = fromFile(entry, variables);
            if (profiles.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException(FILE + ": profile id " + profile.id()
                        + " is used twice or is the reserved id " + ENV_DEFAULT + "; give each profile its own id.");
            }
            addSecret(profile.secret());
        }
        String requested = file == null ? null : file.defaultRunProfile();
        defaultId = requested == null || requested.isBlank() ? ENV_DEFAULT : requested.strip();
        if (!profiles.containsKey(defaultId)) {
            throw new IllegalArgumentException("FACTORY_DEV_FACTORY_DEFAULT_RUN_PROFILE is " + defaultId
                    + ", but no run profile has that id. Use " + ENV_DEFAULT + " or an id from " + FILE + ".");
        }
    }

    /** Only env-default: for a runtime built without the profiles file. */
    public static RunProfiles envOnly(DevRuntimeProperties.ClaudeCode environment) {
        return new RunProfiles(environment, null, name -> null);
    }

    public List<Profile> all() {
        return List.copyOf(profiles.values());
    }

    public Optional<Profile> find(String id) {
        return Optional.ofNullable(id == null ? null : profiles.get(id));
    }

    public Profile defaultProfile() {
        return profiles.get(defaultId);
    }

    /** "high (CLI default)" when no effort is sent. */
    public static String effortLabel(String effort) {
        return effort == null ? "high (CLI default)" : effort;
    }

    /**
     * Checks a run form: the profile must exist and be configured, the effort must be in its list and the
     * turns from 1 to 500. Blank values take the profile's defaults. The message says what to fix.
     */
    public Run choose(String profileId, String effort, String maxTurns, String instructions) {
        Profile profile = profileId == null || profileId.isBlank() ? defaultProfile() : profiles.get(profileId);
        if (profile == null) {
            throw new IllegalArgumentException("There is no run profile " + profileId
                    + ". Reload the page and choose a profile from the list.");
        }
        if (!profile.configured()) {
            throw new IllegalArgumentException(profile.problem());
        }
        String chosenEffort = effort == null || effort.isBlank() ? profile.defaultEffort() : effort.strip();
        if (chosenEffort != null && !profile.efforts().contains(chosenEffort)) {
            throw new IllegalArgumentException("Effort " + chosenEffort + " is not available for profile "
                    + profile.id() + ". Choose one of: " + String.join(", ", profile.efforts()) + ".");
        }
        int turns = profile.maxTurns();
        if (maxTurns != null && !maxTurns.isBlank()) {
            try {
                turns = Integer.parseInt(maxTurns.strip());
            } catch (NumberFormatException e) {
                turns = 0;
            }
            if (turns < 1 || turns > MAX_TURNS) {
                throw new IllegalArgumentException("Max turns must be a whole number from 1 to " + MAX_TURNS + ".");
            }
        }
        return new Run(profile, chosenEffort, turns, instructions(instructions));
    }

    /** Operator instructions are optional plain text of at most 8000 characters; blank means none. */
    public static String instructions(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String instructions = text.strip();
        if (instructions.length() > MAX_INSTRUCTIONS) {
            throw new IllegalArgumentException("Instructions for the agent can have at most " + MAX_INSTRUCTIONS
                    + " characters; this text has " + instructions.length() + ". Shorten it and try again.");
        }
        return instructions;
    }

    /**
     * The profile and values a run uses. Missing settings take the default profile and its defaults. A profile
     * that no longer exists, or settings it no longer allows, fail with a reason that names the profile.
     * It does not check the secret: see {@link Profile#problem()}.
     */
    public Run resolve(CodingRequest.RunSettings settings) {
        String id = settings == null || settings.profileId() == null || settings.profileId().isBlank()
                ? defaultId : settings.profileId();
        Profile profile = profiles.get(id);
        if (profile == null) {
            throw new ProfileException("This run uses profile " + id + ", which is not in " + FILE
                    + " any more. Add the profile back, or retry the task with another profile.");
        }
        String effort = settings == null || settings.effort() == null || settings.effort().isBlank()
                ? profile.defaultEffort() : settings.effort();
        if (effort != null && !profile.efforts().contains(effort)) {
            throw new ProfileException("Profile " + id + " no longer allows effort " + effort + ". Fix "
                    + FILE + ", or retry the task with another effort.");
        }
        // A profile's own turn limit is checked with the profile (see problem()); only a chosen value is checked here.
        Integer chosen = settings == null ? null : settings.maxTurns();
        if (chosen != null && (chosen < 1 || chosen > MAX_TURNS)) {
            throw new ProfileException("Max turns for profile " + id + " must be from 1 to " + MAX_TURNS
                    + ". Retry the task with another value.");
        }
        return new Run(profile, effort, chosen == null ? profile.maxTurns() : chosen,
                settings == null ? null : settings.instructions());
    }

    /** Replaces the secret of every profile, and both legacy Claude Code credentials. */
    public String redact(String value) {
        if (value == null) {
            return null;
        }
        for (String secret : secrets) {
            value = value.replace(secret, "[REDACTED]");
        }
        return value;
    }

    private void addSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
    }

    private static Profile envDefault(DevRuntimeProperties.ClaudeCode environment) {
        String effort = environment.effort() == null || environment.effort().isBlank()
                ? null : environment.effort().strip().toLowerCase(Locale.ROOT);
        String invalid = null;
        try {
            environment.requireConfigured();
        } catch (IllegalStateException e) {
            invalid = "Profile " + ENV_DEFAULT + " cannot run: " + e.getMessage()
                    + ". Fix the FACTORY_CLAUDE_CODE_* settings in .env, then run docker compose up -d again.";
            // The reason above already names a wrong FACTORY_CLAUDE_CODE_EFFORT.
            effort = effort != null && EFFORTS.contains(effort) ? effort : null;
        }
        String variable = "api-key".equals(environment.authentication())
                ? "FACTORY_CLAUDE_CODE_API_KEY" : "FACTORY_CLAUDE_CODE_AUTH_TOKEN";
        return new Profile(ENV_DEFAULT, "Configured in .env", environment.baseUrl(), environment.authentication(),
                variable, environment.secret(), environment.model(), EFFORTS, effort, environment.maxTurns(),
                environment.maxOutputTokens(), invalid);
    }

    private static Profile fromFile(FileProfile entry, Function<String, String> variables) {
        if (entry == null || entry.id() == null || !ID.matcher(entry.id()).matches()) {
            throw new IllegalArgumentException(FILE + ": every profile needs an id of lower-case letters, digits,"
                    + " dots and dashes; found " + (entry == null ? "an empty entry" : entry.id()) + ".");
        }
        String id = entry.id();
        String where = FILE + ", profile " + id + ": ";
        require(entry.model() != null && !entry.model().isBlank(), where + "set model.");
        require("api-key".equals(entry.authentication()) || "bearer-token".equals(entry.authentication()),
                where + "authentication must be api-key or bearer-token.");
        require(entry.secretEnv() != null && VARIABLE.matcher(entry.secretEnv()).matches(),
                where + "secret-env must be the name of an environment variable, such as FACTORY_ZAI_API_KEY.");
        try {
            origin(entry.baseUrl());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "base-url must be an HTTP(S) URL without credentials,"
                    + " query or fragment.");
        }
        List<String> efforts = entry.efforts() == null ? List.of() : List.copyOf(entry.efforts());
        require(!efforts.isEmpty() && EFFORTS.containsAll(efforts),
                where + "efforts must list values from: " + String.join(", ", EFFORTS) + ".");
        require(entry.defaultEffort() != null && efforts.contains(entry.defaultEffort()),
                where + "default-effort must be one of its efforts: " + String.join(", ", efforts) + ".");
        require(entry.maxTurns() != null && entry.maxTurns() >= 1 && entry.maxTurns() <= MAX_TURNS,
                where + "max-turns must be from 1 to " + MAX_TURNS + ".");
        require(entry.maxOutputTokens() != null && entry.maxOutputTokens() > 0,
                where + "max-output-tokens must be positive.");
        String label = entry.label() == null || entry.label().isBlank() ? id : entry.label().strip();
        return new Profile(id, label, entry.baseUrl(), entry.authentication(), entry.secretEnv(),
                variables.apply(entry.secretEnv()), entry.model(), efforts, entry.defaultEffort(), entry.maxTurns(),
                entry.maxOutputTokens(), null);
    }

    /** scheme://host[:port] of an HTTP(S) URL without credentials, query or fragment. */
    public static String origin(String baseUrl) {
        URI endpoint = URI.create(baseUrl == null ? "" : baseUrl);
        boolean http = "http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme());
        if (!http || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Not an HTTP(S) URL without credentials, query or fragment");
        }
        return endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}

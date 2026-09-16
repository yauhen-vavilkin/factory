package org.folio.factory.devfactory.jira;

/**
 * A Jira intake that must not create an execution. {@code code} is stable and
 * machine-readable; {@code transientFailure} tells callers a later retry may succeed.
 */
public class JiraIntakeException extends RuntimeException {
  public static final String INVALID_REQUEST = "INVALID_REQUEST";
  public static final String JIRA_NOT_CONFIGURED = "JIRA_NOT_CONFIGURED";
  public static final String ISSUE_NOT_FOUND = "ISSUE_NOT_FOUND";
  public static final String JIRA_ACCESS_DENIED = "JIRA_ACCESS_DENIED";
  public static final String JIRA_UNAVAILABLE = "JIRA_UNAVAILABLE";
  public static final String REPOSITORY_UNAVAILABLE = "REPOSITORY_UNAVAILABLE";

  private final String code;

  public JiraIntakeException(String code, String message) {
    this(code, message, null);
  }

  public JiraIntakeException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public boolean transientFailure() {
    return JIRA_UNAVAILABLE.equals(code) || REPOSITORY_UNAVAILABLE.equals(code);
  }
}

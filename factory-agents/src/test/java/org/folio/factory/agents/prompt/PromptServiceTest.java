package org.folio.factory.agents.prompt;

import org.folio.factory.agents.llm.PromptLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    private PromptOverrideRepository repository;

    @Mock
    private PromptCatalog catalog;

    @InjectMocks
    private PromptService service;

    @Test
    void resolveFallsBackToClasspathWhenNoOverrideExists() {
        when(latestOf("test-worker", "system")).thenReturn(Optional.empty());

        assertThat(service.resolve("test-worker", "system"))
                .isEqualTo(PromptLoader.load("test-worker", "system"))
                .contains("You are a test worker");
    }

    @Test
    void resolveReturnsOverrideContent() {
        when(latestOf("test-worker", "system"))
                .thenReturn(Optional.of(content("CUSTOM SYSTEM PROMPT", 2)));

        assertThat(service.resolve("test-worker", "system")).isEqualTo("CUSTOM SYSTEM PROMPT");
    }

    @Test
    void resolveFallsBackToClasspathWhenLatestIsUseDefault() {
        when(latestOf("test-worker", "system")).thenReturn(Optional.of(revert(3)));

        assertThat(service.resolve("test-worker", "system"))
                .isEqualTo(PromptLoader.load("test-worker", "system"));
    }

    @Test
    void saveOverrideStartsAtVersionOne() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);
        when(latestOf("test-worker", "system")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptOverride saved = service.saveOverride("test-worker", "system", "new content", "alice");

        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.isUseDefault()).isFalse();
        assertThat(saved.getContent()).isEqualTo("new content");
        assertThat(saved.getCreatedBy()).isEqualTo("alice");
    }

    @Test
    void saveOverrideIncrementsVersionFromLatest() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);
        when(latestOf("test-worker", "system")).thenReturn(Optional.of(content("v1", 1)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveOverride("test-worker", "system", "v2", "alice");

        ArgumentCaptor<PromptOverride> captor = ArgumentCaptor.forClass(PromptOverride.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(2);
    }

    @Test
    void saveOverrideRejectsBlankContent() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);

        assertThatThrownBy(() -> service.saveOverride("test-worker", "system", "   ", "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveOverrideRejectsBlankAuthor() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);

        assertThatThrownBy(() -> service.saveOverride("test-worker", "system", "content", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveOverrideRejectsUnknownPair() {
        when(catalog.exists("ghost", "system")).thenReturn(false);

        assertThatThrownBy(() -> service.saveOverride("ghost", "system", "content", "alice"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void saveOverrideTranslatesConcurrentInsertToIllegalState() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);
        when(latestOf("test-worker", "system")).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.saveOverride("test-worker", "system", "content", "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concurrently");
    }

    @Test
    void revertToDefaultWritesUseDefaultRow() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);
        when(latestOf("test-worker", "system")).thenReturn(Optional.of(content("custom", 2)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptOverride reverted = service.revertToDefault("test-worker", "system", "alice");

        assertThat(reverted.isUseDefault()).isTrue();
        assertThat(reverted.getContent()).isNull();
        assertThat(reverted.getVersion()).isEqualTo(3);
    }

    @Test
    void revertToDefaultRejectedWhenNoOverrideExists() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);
        when(latestOf("test-worker", "system")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revertToDefault("test-worker", "system", "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revertToDefaultRejectedWhenLatestIsAlreadyUseDefault() {
        when(catalog.exists("test-worker", "system")).thenReturn(true);
        when(latestOf("test-worker", "system")).thenReturn(Optional.of(revert(4)));

        assertThatThrownBy(() -> service.revertToDefault("test-worker", "system", "alice"))
                .isInstanceOf(IllegalStateException.class);
    }

    private Optional<PromptOverride> latestOf(String workerId, String promptName) {
        return repository.findTopByWorkerIdAndPromptNameOrderByVersionDesc(workerId, promptName);
    }

    private static PromptOverride content(String content, int version) {
        return new PromptOverride("test-worker", "system", version, false, content, "alice");
    }

    private static PromptOverride revert(int version) {
        return new PromptOverride("test-worker", "system", version, true, null, "alice");
    }
}

# FACTORY IMPLEMENTATION INPUT

Дата решения: 2026-09-10. Область: coding subsystem FOLIO Dev Factory. Архитектура HYBRID зафиксирована; свой coding loop не разрабатывать.

## 1. Выбранный runtime

**PRIMARY: Pi 0.85.1**, проект `earendil-works/pi`, пакет `@earendil-works/pi-coding-agent@0.85.1`. Использовать полный coding-agent runtime, а не строить агента на `pi-agent-core`.

**Integration: native `pi --mode rpc`, UTF-8 JSONL через stdin/stdout, без TTY.** Java adapter управляет процессом и protocol lifecycle. TypeScript SDK bridge, fork Pi, свой tool dispatcher и свой compactor не нужны.

**FALLBACK: OpenCode 1.18.30**, интеграция через private per-run `opencode serve`, HTTP API и SSE. Реализовывать fallback только после конкретного Pi-specific NO-GO. Не переключать runtime или модель незаметно в середине attempt.

Решение основано на документации и исходниках, не на измеренном превосходстве coding quality. Confidence: MEDIUM до execution spike.

## 2. Топология и ownership

**Топология C:** Pi, repository и developer tools внутри disposable sandbox; model traffic идёт через controlled proxy вне этого sandbox.

```text
Factory / standalone spike driver
  -> prepared sandbox + PiRpcCodingRunner
       -> Pi -> native read/search/edit/write/bash + compaction
       -> fixed-route model proxy -> provider
  -> stop complete coding workload
  -> freeze candidate
  -> independent verification in clean environment
  -> HarnessResult
```

Proxy — не второй harness и не конвертер протоколов. Первоначально он пересылает OpenAI Chat Completions к одному заданному Z.ai upstream, сохраняет SSE/usage, проверяет model и разрешённый output limit. Upstream API key остаётся вне coding sandbox; внутри только ограниченный per-run token. Proxy не повторяет запросы автоматически, не меняет модель и не разрешает произвольные upstream URLs.

Factory owns: task contract; sandbox lifecycle; network/resource/time limits; cancellation; candidate snapshot/export; independent checks; artifacts; telemetry normalization; final outcome. PR/Jira workflows остаются вне coding runtime.

Pi owns: полный model/tool loop, exploration, editing, exploratory tests, debugging, provider integration, context compaction, session persistence. Self-review задаётся инструкцией, но не считается независимым review.

## 3. Минимальная интеграционная граница

Предлагаемый `CodingRunner.run(request, eventSink, cancellation)` принимает `runId`, immutable task contract, prepared-sandbox reference, repo path, base SHA, execution/model profiles, instruction manifest, budget и artifact destination. Возвращает `CodingAttempt`: runner stop reason, process exit, session reference, agent report, telemetry coverage, diagnostic references. `CodingRunner` не объявляет final SUCCESS.

Добавить отдельный sandbox process-session interface: argv/cwd/env, stdin, stdout, stderr, awaitExit, terminate и kill всей workload boundary. Не пытаться использовать существующий блокирующий `SandboxService.exec(String)` как RPC transport. Сохранить его для подходящих коротких команд/verification.

Один процесс Pi обслуживает один task attempt. Хранить raw protocol и normalized events отдельно. Читать stdout и stderr параллельно; stdin закрывать только после завершения и получения результатов.

## 4. Установка и запуск

Build-time установка:

```bash
npm install --prefix /opt/pi --save-exact --omit=dev \
  @earendil-works/pi-coding-agent@0.85.1
```

Сохранить dependency lock; последующие сборки — `npm ci` по этому lock. Зафиксировать final OCI image digest, версии Node/JDK/Maven и hashes конфигурации. Runtime не устанавливает пакеты и не обновляется во время task.

Pi npm package требует Node >=22.19.0. В image использовать конкретную поддерживаемую patch-версию Node и записать её, а не полагаться на floating tag.

Environment:

```text
HOME=/home/agent
PI_CODING_AGENT_DIR=/state/pi
TMPDIR=/state/tmp
PI_OFFLINE=1
PI_TELEMETRY=0
FACTORY_MODEL_TOKEN=<run-scoped proxy token>
```

`PI_OFFLINE=1` отключает startup network/update operations, не LLM API calls.

Запуск с cwd `/workspace/repo`:

```bash
/opt/pi/node_modules/.bin/pi \
  --mode rpc --provider factory-zai --model glm-5.3-flash --thinking high \
  --no-approve --no-extensions --no-skills --no-prompt-templates \
  --no-themes --no-context-files \
  --tools read,bash,edit,write,grep,find,ls \
  --append-system-prompt "$(cat /run/factory/core.md)" \
  --session-dir /state/pi/sessions
```

В Java передавать argv напрямую; содержимое core.md — один аргумент. Task передавать JSON-сериализатором в RPC, не shell interpolation. Все `/run/factory/*` — новые generated inputs этой интеграции.

Протокол: `get_state` -> проверить точный provider/model -> `prompt` -> читать events до **`agent_settled`** -> `get_state`, `get_session_stats`, `get_last_assistant_text`; сохранить session JSONL/entries -> закрыть stdin -> дождаться выхода -> гарантированно остановить остаточные процессы sandbox. `prompt.success` означает acceptance, `agent_end` может предшествовать automatic retry; ни они, ни exit 0 не означают SUCCESS.

Отмена: `clear_queue` -> `abort`; grace 5 s -> SIGTERM с grace 5 s -> принудительный stop/kill всей OCI cgroup или VM workload. Pi native bash убивает process group, но это не гарантия удаления detached descendants. На SIGTERM финальный stdout может не успеть flush: сохранять события непрерывно.

## 5. Model profile

Использовать Pi `models.json` с custom provider `factory-zai`, `api: "openai-completions"`, `baseUrl` controlled proxy, `apiKey: "$FACTORY_MODEL_TOKEN"`. Bare uppercase string не является env reference. Не разрешать shell-command values вида `!command`.

Начальный requested ID — `glm-5.3-flash`, уже присутствующий в Factory config. Существование GLM-5.3-Flash подтверждено официальными материалами Z.ai, но доступность этого точного service ID на выбранном account/endpoint в исследовании не проверена; публичная API reference отстаёт от model announcements. Обязателен preflight: text + multi-turn tool call + usage + проверка requested/reported model. При несовпадении или неизвестном ID — configuration failure, без fallback на другую модель.

Основной upstream для Factory: metered General API `https://api.z.ai/api/paas/v4`. Coding Plan — отдельный профиль `https://api.z.ai/api/coding/paas/v4`; Pi официально перечислен как supported tool, но это не разрешение на любой multi-user/service scenario. Не путать endpoints и billing. Встроенный Pi provider `zai` по умолчанию использует Coding Plan endpoint, поэтому здесь выбран явный custom provider.

Для initial GLM profile: reasoning=true, thinking level high; `compat.thinkingFormat="zai"`, `supportsReasoningEffort=true`, `supportsDeveloperRole=false`, `maxTokensField="max_tokens"`, `supportsStrictMode=false`, `requiresReasoningContentOnAssistantMessages=true`. Проверить реальные wire fields и replay reasoning/tool history в spike. Ограничить runtime context до 65536 и output до 16384 как начальные Factory caps, а не как заявление о максимуме модели; preflight должен подтвердить, что endpoint допускает эти значения.

Native compaction: enabled, reserveTokens=16384, keepRecentTokens=20000. Native agent retries: maxRetries=3, baseDelayMs=2000. Provider/SDK retries=0, provider timeout=300000 ms. Все параметры фиксируются в manifest; второй retry layer в proxy запрещён.

## 6. Sandbox и Java

Non-root UID; read-only root filesystem; writable `/workspace/repo`, HOME, `/state/pi`, `/state/tmp`, isolated Maven cache. Config/core/skills mounted read-only, authoritative Factory artifacts записываются вне coding sandbox. Не выдавать host Docker socket, SSH agent, developer HOME, Jira/GitHub credentials или host mounts. Ограничить CPU/RAM/PIDs/disk; разрешить egress только к model proxy и утверждённым dependency mirrors.

Image: Pi, Node, bash, git, rg, coreutils/findutils, CA certificates, выбранный JDK и Maven. Сделать profiles JDK17/JDK21. Maven wrapper использовать при наличии, с зафиксированными distribution settings; иначе pin system Maven. Не использовать shared writable dependency cache между недоверенными runs.

Unit-only profile достаточно для первого spike. Testcontainers требует отдельного `java-integration` profile: disposable per-run VM со своим Docker daemon. Доступ к этому daemon означает, что вся VM недоверенная. Не размещать рядом другие tasks или secrets. Связность mapped ports/Ryuk проверить отдельно; verification выполняется в новом clean environment. Отсутствие обязательной IT infrastructure даёт INCOMPLETE, не SUCCESS с пропущенными тестами.

## 7. Instructions / skills

Сохранить native Pi system prompt, добавить небольшой Factory policy через `--append-system-prompt`. Автоматическую загрузку project config/extensions/context files выключить. Утверждённые repository AGENTS.md читать как текст из base SHA и включать с hashes/paths; они не могут менять Factory contract или permissions.

TaskContract содержит цель, acceptance criteria, scope и required checks. Начать без skills в smoke; затем максимум два curated skills (`folio-java`, `java-verification`) через `--no-skills --skill /run/factory/skills/...`. Testcontainers material — только для соответствующего profile. Не создавать каталог из десятков skills и не загружать весь FOLIO context постоянно.

## 8. Verification и artifacts

После полной остановки coding workload экспортировать изменения относительно frozen base SHA, включая untracked files, deletions, modes, binary content и commits, созданные агентом. Не принимать display `git diff` за complete candidate. Проверять candidate в clean checkout; не доверять изменённому агентом `.git/config`, hooks или текущему индексу при trusted export.

Обязательные checks задаются Factory trusted profile/contract, не final response. Evidence связывать с `candidateId`, `executionProfileDigest`, `checkId`, command и результатами. Проверять реальные test reports, expected suite execution и skip policy; не использовать regex по обрезанному Maven output как окончательный verdict. Legitimate test changes допустимы; ослабление tests/build guards должно обнаруживаться review/check policy.

Итоговые outcomes: SUCCESS=eligible for review, FAILED, INCOMPLETE, ERROR, CANCELLED. No-op запрещён, кроме явно разрешённого и проверенного. Данные сохраняются до удаления sandbox; отсутствие evidence не превращать в success.

Artifacts: input/config/instruction manifests, raw RPC JSONL, normalized events, session JSONL/entries, agent final text, candidate.patch + manifest, proxy request metadata/usage, verification stdout/stderr/XML, structured result.

## 9. Telemetry и ограничения

Native RPC даёт tool names/arguments/results, bash command, assistant messages, retry и compaction events. `get_session_stats` включает assistant и summarization usage; не складывать его повторно с per-call totals. Streaming usage cumulative, не additive.

Proxy даёт observable upstream request count, request latency, requested model, response.model и raw usage, когда provider их прислал. Модель за server alias независимо доказать нельзя. Unknown usage/cost/cache fields = null, не 0. Нулевой native cost для custom model не означает бесплатные вызовы.

Pi model-tool bash объединяет stdout/stderr; structured numeric exit code не гарантируется единым tool-result schema. При truncation есть `fullOutputPath`; копировать только допустимые artifact paths без symlink escape. Hard kill может оставить неполный output. Independent verifier обязан сохранять отдельные stdout/stderr и exit codes.

Telemetry — диагностические данные, не доверенное доказательство correctness. Не обещать скрытый reasoning или точные внутренние provider retries, которые не экспортируются.

## 10. Spike и GO/NO-GO

Полная Factory, database и Jira для spike не нужны. Маленький Java driver использует тот же CodingRunner contract, prepared OCI sandbox и tiny Maven fixture. Task: реализовать нормализацию списка строк (trim, исключение null/blank, deduplication с сохранением порядка); independent tests находятся вне coding workspace.

Сначала scripted API responses проверяют real Pi tools и protocol mechanics. Потом 3 live smoke attempts с квалифицированным GLM profile. Это не coding-quality benchmark.

Обязательные проверки: non-root/no-TTY/readonly startup; model mismatch/401/429; pending retry после agent_end; large command output >=2 MiB и доступ к ранней ошибке; native compaction с сохранением constraints; cancellation shell + descendants; сохранение partial changes после crash; отсутствие autoload project extension; отсутствие provider key в sandbox; final verification rejecting intentionally wrong candidate.

GO: поддерживаемый protocol работает без fork/TTY; final state определяется надёжно; требуемая trajectory и provider-reported usage сохраняются; весь workload останавливается; partial candidate не теряется; независимые failed checks блокируют SUCCESS.

NO-GO для Pi: эти требования недостижимы через документированный interface/configuration без существенной переписки runtime. Тогда переключить adapter на OpenCode, сохранив contracts. Неверный API key, отсутствующая модель в аккаунте или неработающий Docker host — environment/provider blockers, а не автоматический NO-GO для Pi.

После GO выполнить 8-task FOLIO benchmark Low -> Medium -> High. Custom harness не нужно предварительно доводить до production. Первый сравнительный внешний runner — OpenCode с той же моделью. До grading согласовать обнаруженный конфликт MODSCHED-70: task/rubric требуют более широких retries, чем human solution. Не оценивать правильность только по сходству patch и не считать scripted eval-pack live quality evidence.

## Первичные источники

- Pi pinned README/CLI: https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/README.md
- Pi RPC: https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/rpc.md
- Pi models/config: https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/models.md
- Pi settings: https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/settings.md
- Pi compaction: https://github.com/earendil-works/pi/blob/v0.85.1/packages/coding-agent/docs/compaction.md
- OpenCode server: https://opencode.ai/docs/server/
- Z.ai tool support: https://docs.z.ai/devpack/tool/others
- Z.ai API: https://docs.z.ai/api-reference/llm/chat-completion
- Factory inspected revision: https://github.com/yauhen-vavilkin/factory/tree/4ce0ba7d29e34b287f1664ea246a86dd50d34213

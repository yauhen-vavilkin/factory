# Quickstart — локальный запуск и онбординг

_2026-09-10. Короткая инструкция: поднять Factory локально, запустить первую задачу и понять, что в этом репо происходило. Для человека и для агента._

## Что это и что здесь происходило

**FOLIO AI SDLC Factory** — AI-оркестрация SDLC: flows — это YAML-данные, stateless-воркеры общаются только через immutable versioned artifacts, каждое значимое действие проходит через gate. Архитектура — `README.md`.

Над этим форком работал автономный инженерный цикл (AutoDev):

- **T9–T25 приняты** — живут в `main` как сквош-коммиты `task(TXX): …`. Темы: surefire shutdown, sandbox wiring, dev-flow, e2e, dogfood, task contract, full change export, early completion, eval-pack, идемпотентный inbox, adapter telemetry, verified outcome (identity-bound evidence), durable output.
- **T26** (`dev/T26-eval-pack-counterexamples`) — последняя незакрытая задача: контрпримеры к eval-pack (false-verification, durability). На момент написания 12/12 тестов зелёные, acceptance pending.
- `evals/` — бенчмарк Sprint 248 от владельца: 8 реальных задач FOLIO (tiers low/medium/high), чистые промпты в `evals/tasks/`, эталонные PR + рубрики в `evals/solutions/`.
- Контракты задач: `docs/inbox-admission.md`, `docs/eval-pack.md`, `docs/quality-backlog.md`.

Ветки: `main` = принятая работа; `dev/T26-*` = самый свежий код (ещё не принят).

## Быстрый старт (macOS)

Prerequisites: JDK 21, Maven 3.9+, Docker Desktop, git.

```bash
git clone git@github.com:yauhen-vavilkin/factory.git
cd factory
git checkout dev/T26-eval-pack-counterexamples   # свежий код; main — только принятая работа

# 1) база (postgres:16 -> localhost:5432, db/user/pass = factory)
docker compose up -d

# 2) ключ LLM в файл (не в argv, не в shell history)
install -m 600 /dev/null ~/.factory/api-key
echo 'ВАШ_ZAI_КЛЮЧ' > ~/.factory/api-key

# 3) сборка
mvn -pl factory-app -am package -DskipTests

# 4) запуск — ключ возьмётся из файла, модель glm-5.3
./scripts/run-local.sh
```

Boot ~10–15 с, Tomcat на `:8080`. Проверка: `curl localhost:8080/actuator/health`.

Без ключа приложение тоже стартует, но LLM-шаги выполняться не будут.

## Первая задача в инбокс

Приложение опрашивает `FACTORY_INBOX_DIR` (по умолчанию `./tasks-inbox`) раз в 5 с. Положи YAML:

```yaml
id: hello-001
repo: /Users/you/dev/some-git-repo   # абсолютный путь к git-репо для кодинг-воркера
goal: |
  Add a "Local run" section to README describing how to start the project.
base: main
branch: task/hello-001
acceptance:
  - README contains the section
notes: первый локальный smoke
```

Правила схемы:

- `id` / `repo` / `goal` обязательны; лишние top-level ключи → exception.
- `base` default `main`, `branch` default `task/<id>`; оба валидируются как refname/SHA.
- Повторная загрузка того же файла не создаёт вторую задачу (идемпотентность, T22).

В логах увидишь admission → coding-worker. Ветка `task/hello-001` создаётся приложением в указанном репо.

Кодинг-воркеры исполняются в docker-песочнице: образ по умолчанию `maven:3.9-eclipse-temurin-21`, Maven-кэш в volume `factory-m2-cache`. Свой образ (JDK 21 + git): `factory-sandbox/build-image.sh` → `factory-sandbox:jdk21`.

## Тесты

```bash
mvn test                                   # весь reactor
mvn -pl factory-flow-dev-factory -am test  # модуль dev-flow
```

На `8055cb8` (T26) — 12/12 зелёные, включая `partialPersistBundleCarriesWrittenSubsetOnlyAndNeverAccepted`.

## Главные env (`factory-app/src/main/resources/application.yaml`)

| env | default | что делает |
|---|---|---|
| `FACTORY_DB_URL` | `jdbc:postgresql://localhost:5432/factory` | база (Flyway миграции — при старте) |
| `FACTORY_DB_USER` / `FACTORY_DB_PASSWORD` | `factory` / `factory` | база |
| `ANTHROPIC_API_KEY` | — | ключ LLM; `run-local.sh` берёт из файла |
| `FACTORY_LLM_BASE_URL` | `https://api.z.ai/api/anthropic` | провайдер |
| `FACTORY_LLM_MODEL` | `claude-sonnet-4-5` | модель кодинг-воркера (`run-local.sh` ставит `glm-5.3`) |
| `FACTORY_HARNESS_MODEL` | `glm-5.3-flash` | модель harness-протокола |
| `FACTORY_INBOX_DIR` | `tasks-inbox` | каталог инбокса |
| `FACTORY_SANDBOX_MODE` | `docker` | режим песочницы |

## Для агента, открывшего этот репо

1. Читай по порядку: `README.md` (архитектура) → этот файл (запуск) → `docs/` (контракты).
2. Секреты: ключ LLM только из файла (`~/.factory/api-key`, права 0600). Никогда не коммить ключи и не передавай их в argv/логи.
3. Ветки `task/*` в целевых репо создаёт само приложение; в `main` фабрики не коммить без принятой задачи.
4. Проверка сборки перед работой: `mvn -q -pl factory-flow-dev-factory -am test`.
5. Качество решений меряется бенчмарком: `evals/tasks/` + эталоны `evals/solutions/`.

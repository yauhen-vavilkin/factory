#!/usr/bin/env python3
"""Advisory semantic critique experiment (Developer Flow M3).

One fresh model context per frozen candidate: the reviewer sees only the task
text and the exact candidate patch (never ground-truth solutions), and returns
classified findings. The output is written next to, never into, run evidence;
nothing in Factory reads it for routing.

Usage: critique.py TASK_MD CANDIDATE_PATCH OUT_JSON [--model MODEL]
Reads FACTORY_MODEL_API_KEY (or GLM_API_KEY) and FACTORY_MODEL_BASE_URL from
the environment or the repository .env file. The key is never printed.
"""
import argparse
import hashlib
import json
import os
import pathlib
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[3]

SYSTEM = """You are an independent senior reviewer of a proposed code change for a FOLIO backend module.
You did not write the change and you must not rewrite it.
Judge ONLY whether the frozen candidate patch does what the task means and is safe to propose:
semantic mismatch with the task or acceptance criteria, missing required behaviour, scope mistakes
(unrelated or out-of-scope edits), unsafe implementation choices, or requirement violations.
Do NOT report style, formatting, naming preferences, or anything a compiler, unit test run or
Checkstyle would already catch. Do not assume facts about files you cannot see; if the patch
alone cannot establish a claim, say so in the evidence and lower your confidence.

Answer with one JSON object and nothing else:
{
  "verdict": "ACCEPT" | "REPAIR" | "DECISION_REQUIRED",
  "summary": "<one or two sentences>",
  "findings": [
    {
      "class": "REPAIRABLE_DEFECT" | "DECISION_REQUIRED" | "NOTE",
      "blocking": true | false,
      "criterion": "<acceptance criterion id or requirement it concerns, or null>",
      "file": "<path from the patch or null>",
      "claim": "<what is wrong or missing>",
      "evidence": "<exact patch lines or task text supporting the claim>",
      "confidence": "HIGH" | "MEDIUM" | "LOW"
    }
  ]
}
Use verdict ACCEPT when no blocking finding exists."""


def load_env():
    env = {}
    dotenv = ROOT / ".env"
    if dotenv.is_file():
        for line in dotenv.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                env[key.strip()] = value.strip()
    env.update({k: v for k, v in os.environ.items() if k.startswith(("FACTORY_", "GLM_"))})
    return env


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("task")
    parser.add_argument("patch")
    parser.add_argument("out")
    parser.add_argument("--model", default=None)
    args = parser.parse_args()
    env = load_env()
    key = env.get("FACTORY_MODEL_API_KEY") or env.get("GLM_API_KEY")
    if not key:
        raise SystemExit("no model API key configured")
    base = env.get("FACTORY_MODEL_BASE_URL", "https://api.z.ai/api/coding/paas/v4").rstrip("/")
    model = args.model or env.get("FACTORY_MODEL_ID", "glm-5.3-flash")
    task = pathlib.Path(args.task).read_text(encoding="utf-8")
    patch = pathlib.Path(args.patch).read_text(encoding="utf-8")
    user = f"TASK\n====\n{task}\n\nFROZEN CANDIDATE PATCH\n======================\n{patch}"
    body = json.dumps({"model": model, "temperature": 0, "max_tokens": 8192,
                       "messages": [{"role": "system", "content": SYSTEM},
                                    {"role": "user", "content": user}]}).encode()
    request = urllib.request.Request(base + "/chat/completions", data=body, method="POST",
                                     headers={"Content-Type": "application/json",
                                              "Authorization": "Bearer " + key})
    started = time.time()
    with urllib.request.urlopen(request, timeout=600) as response:
        raw = json.loads(response.read().decode())
    content = raw["choices"][0]["message"].get("content") or ""
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text[text.find("{"):]
    try:
        review = json.loads(text[text.find("{"):text.rfind("}") + 1])
    except ValueError:
        review = {"verdict": "UNPARSEABLE", "raw": content}
    record = {
        "schema": "DevFlowAdvisoryCritique/v1",
        "advisory": True,
        "model": model,
        "task": str(pathlib.Path(args.task).resolve().relative_to(ROOT))
        if pathlib.Path(args.task).resolve().is_relative_to(ROOT) else args.task,
        "candidatePatchSha256": hashlib.sha256(patch.encode()).hexdigest(),
        "durationMs": int((time.time() - started) * 1000),
        "usage": raw.get("usage"),
        "review": review,
    }
    pathlib.Path(args.out).write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": args.out, "verdict": review.get("verdict"),
                      "findings": len(review.get("findings", []))}))


if __name__ == "__main__":
    main()

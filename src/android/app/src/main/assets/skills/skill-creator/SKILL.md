---
name: skill-creator
description: Guide for creating effective skills. Trigger when users want to create a new skill or update an existing one (frontmatter, evals.json, description conventions, scripts).
version: 2.2.0
---
# Skill Creator

This skill provides guidance for creating effective skills.

## About Skills

Skills are modular, self-contained packages that extend Claude's capabilities by providing
specialized knowledge, workflows, and tools. Think of them as "onboarding guides" for specific
domains or tasks—they transform Claude from a general-purpose agent into a specialized agent
equipped with procedural knowledge that no model can fully possess.

### What Skills Provide

1. Specialized workflows - Multi-step procedures for specific domains
2. Tool integrations - Instructions for working with specific file formats or APIs
3. Domain expertise - Company-specific knowledge, schemas, business logic
4. Bundled resources - Scripts, references, and assets for complex and repetitive tasks

## Core Principles

### Concise is Key

The context window is a public good. Skills share the context window with everything else Claude needs: system prompt, conversation history, other Skills' metadata, and the actual user request.

**Default assumption: Claude is already very smart.** Only add context Claude doesn't already have. Challenge each piece of information: "Does Claude really need this explanation?" and "Does this paragraph justify its token cost?"

Prefer concise examples over verbose explanations.

### Set Appropriate Degrees of Freedom

Match the level of specificity to the task's fragility and variability:

- **High freedom (text-based instructions)**: Use when multiple approaches are valid.
- **Medium freedom (pseudocode or scripts with parameters)**: Use when a preferred pattern exists.
- **Low freedom (specific scripts, few parameters)**: Use when operations are fragile, consistency is critical, or a specific sequence must be followed.

### Anatomy of a Skill

Every skill consists of a required SKILL.md file and optional bundled resources:

```
skill-name/
├── SKILL.md (required)
│   ├── YAML frontmatter (name + description required)
│   └── Markdown instructions
└── Bundled Resources (optional)
    ├── scripts/       - Executable code
    ├── references/    - Documentation loaded as needed
    └── assets/        - Files used in output (templates, icons, etc.)
```

#### SKILL.md Frontmatter

- `name` (required): The skill name
- `description` (required): What the skill does and when to trigger it. Be comprehensive—this is the primary triggering mechanism.

#### SKILL.md Body

Instructions and guidance, loaded after the skill triggers. Keep under 500 lines; split into reference files when approaching this limit.

### Progressive Disclosure

Skills use three loading levels:
1. **Metadata** - Always in context (~100 words)
2. **SKILL.md body** - When skill triggers (<5k words)
3. **Bundled resources** - As needed (unlimited)

## Skill Creation Process

1. **Understand** the skill with concrete examples from the user
2. **Plan** reusable contents (scripts, references, assets)
3. **Create** the SKILL.md with proper frontmatter and instructions
4. **Test** by using the skill on real tasks
5. **Iterate** based on actual usage

### Writing the SKILL.md

- Use imperative/infinitive form
- `description` field should include all "when to use" triggers (body is loaded after triggering)
- Only add context Claude doesn't already have
- Prefer concise examples over verbose explanations
- Keep essential workflow in SKILL.md; move detailed reference material to separate files

### evals.json (trigger verification — write one for every new skill)

One `evals.json` per skill, 3-5 entries. The prompt is deliberately casual (real user phrasings);
the first assertion is always the trigger check — this makes "did it trigger / did it misfire" a
verifiable item instead of a hope.

```json
{"skill": "<name>", "version": "1.0.0", "evals": [
  {"id": 1, "prompt": "<real casual sentence>", "expect_triger": true,
   "assertions": ["触发检查：...", "<key action that must happen>"]}]}
```

Rules: first assertion must mention 触发/trigger; include at least one positive prompt with **no**
trigger word (casual-drift case) and at least one negative prompt (`expect_triger: false`).

Checker (same dir as this file): `scripts/check_evals.py static` — format + trigger-word coverage
(reads 「」-quoted trigger words from the skill's description); `--strict` also fails skills with no
evals.json. `scripts/check_evals.py run <skill>` — behavioural: calls a real model via
minis-model-use and compares the verdicted trigger against `expect_triger`. Both have `--self-test`
(fixture + reverse control).

`run` is rate-limited by design since 2026-09-26: `--interval <s>` (default 5, 0 = off) spaces
the calls out and a rate-limited/empty call is retried once after `--retry-wait <s>` (default 15).
Its verdicts are three-state — `PASS` / `FAIL` (we got a readable answer: evidence about the
trigger face) vs `UNKNOWN` (no readable output: no output file / 429 / empty response: evidence
about the *call*, not the trigger face). Exit codes: `0` all matched, `1` at least one FAIL,
`3` no FAIL but ≥1 UNKNOWN — **never** record a 3 as a pass, re-run it. Before this split, a
gateway rate-limit storm turned a whole batch into "trigger-face failures" (2026-09-25: 25 × 429
in three minutes) and polluted the evals data.

**Changing a `description` requires one behavioural `run`** — the description IS the trigger face.
Empirically verified: a single tightening flipped the same prompt's verdict outright. Static checks
cannot see that; only a real call can.

### What NOT to Include

Do not create extraneous files: README.md, INSTALLATION_GUIDE.md, CHANGELOG.md, etc. The skill should only contain what an AI agent needs to do the job.
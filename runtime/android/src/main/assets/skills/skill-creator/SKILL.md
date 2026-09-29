---
name: skill-creator
description: Create or update a reusable SKILL.md for the current coding agent when the user asks for a skill.
---

# Skill Creator

Create a skill as a directory containing `SKILL.md`. Use only the current agent's available file and shell tools; this skill requires no account, hosted API, or provider-specific command.

1. Ask for missing purpose or constraints only when they cannot be inferred. Read relevant project instructions and existing skills before changing files.
2. Choose a short lowercase, hyphenated directory name. Start `SKILL.md` with YAML frontmatter containing the matching `name` and a specific `description` that says when the skill applies.
3. Write focused instructions for the requested outcome. Refer to tools by capability rather than a vendor-specific tool name. Add scripts or references only when the workflow needs them. Do not embed credentials or private data.
4. For a personal skill, prepare identical copies in all four discovery directories: `~/.agents/skills/<name>` for Codex, `~/.claude/skills/<name>` for Claude Code, `~/.config/opencode/skills/<name>` for OpenCode, and `~/.pi/agent/skills/<name>` for Pi. For project scope Pi uses `.pi/skills/<name>`; the other agents use their corresponding project directories. Check all four destinations before writing; do not replace an existing skill unless the user explicitly requested it. Add an empty `.mobby-shared` marker beside each `SKILL.md` so the App can show the skill in its shared catalogue.
5. After writing, read back all four copies and check their name, frontmatter, content, paths, and links. Explain where the skill was saved and how each agent can invoke it.

The instructions and supporting files should also make sense to another coding agent with ordinary file and shell access. If a step depends on a particular integration, describe that dependency explicitly instead of claiming portability.

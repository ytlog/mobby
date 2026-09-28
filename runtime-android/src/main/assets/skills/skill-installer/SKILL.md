---
name: skill-installer
description: Install a user-selected SKILL.md from a local directory or Git repository into the current coding agent.
---

# Skill Installer

Install skills from a source the user selects. This workflow uses ordinary file and Git operations; it has no default vendor catalogue, account, or hosted API.

1. Identify the requested source and skill directory. If the source is a remote repository, use the current agent's available Git or network access to fetch it into a temporary directory. Do not execute scripts from the source just to inspect it.
2. Read `SKILL.md` and its frontmatter. Confirm the directory name matches `name`, the instructions are readable, and any referenced supporting files are present. Show the source and any external service or credential dependency before installing.
3. For a personal skill, install identical copies in all four discovery directories: `~/.agents/skills/<name>` for Codex, `~/.claude/skills/<name>` for Claude Code, `~/.config/opencode/skills/<name>` for OpenCode, and `~/.pi/agent/skills/<name>` for Pi. For project scope Pi uses `.pi/skills/<name>`; the other agents use their corresponding project directories.
4. Check all destinations first. Refuse to overwrite an existing same-name skill without the user's explicit instruction. Copy the skill directory, excluding Git metadata and unrelated repository files. Keep paths inside each destination; never follow a source symlink outside the chosen skill directory. Add an empty `.mobby-shared` marker beside each `SKILL.md` so the App can list the skill in its shared catalogue.
5. Read back all installed copies, report their locations and any remaining dependencies, and explain that new agent sessions may be needed for discovery. Clean up only the temporary checkout created for this installation.

An installed skill can still depend on provider-specific tools. Installing it does not make those dependencies portable; disclose them rather than silently changing its behavior.

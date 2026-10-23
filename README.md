<p align="center">
  <img src="src/main/resources/icons/cliq.svg" width="120" alt="Cliq AI Companion" />
</p>

<h1 align="center">Cliq AI Companion</h1>

<p align="center">
  A graphical cockpit for Claude Code and Gemini CLI agents — built natively into your JetBrains IDE.
</p>

<p align="center">
  Cliq connects your IDE, terminal, and project context into one workflow.<br/>
  Launch AI CLI agents in one click, share live workspace context through MCP,<br/>
  and review every proposed file change as a native IDE diff.
</p>

<p align="center">
  🌐 Languages: <a href="README.md">English</a> | <a href="README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.0-01B43C.svg" />
  <img src="https://img.shields.io/badge/JetBrains%20Platform-2024.3%2B-000000.svg" />
  <img src="https://img.shields.io/badge/since--build-243-blue.svg" />
  <img src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF.svg" />
  <img src="https://img.shields.io/badge/JVM-21-ED8B00.svg" />
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" />
</p>

---

## Overview

Claude Code, Gemini CLI, Qwen Code are powerful tools, but using them inside an IDE often means switching between terminal, editor, and project files.

Cliq removes that friction. It runs agents inside the JetBrains terminal, shares active workspace context, and turns AI-generated edits into real IDE diffs you can inspect, modify, accept, or reject.

Cliq does not replace your agent or bundle a model. It adds the missing IDE integration layer.

---

## Key Features

- **One-click agent launcher**  
  Start Claude, Gemini, Qwen from the tool window, toolbar, Tools menu, or `Ctrl+Alt+Q`.

- **Native IDE diff reviews**  
  AI changes open as side-by-side diffs with syntax highlighting, editable proposals, and per-hunk actions.

- **Live workspace context sync**  
  Shares active files, cursor position, selection, and recent files with the running agent.

- **Built-in MCP server**  
  Local loopback MCP server with secure token-based access.

- **Files Context panel**  
  Pin files, track recently opened files, and send `@file` references directly to the terminal.

- **Prompt composer**  
  Build prompts with optional file context and send instantly to the active terminal session.

- **Auto-apply mode**  
  Trusted autonomous runs can write changes directly without review.

- **Pull / merge request review context (GitHub and GitLab)**  
  Pick an open GitHub pull request or GitLab merge request (or type its number) and Cliq collects diffs, full files, related tests, CI status, failed job logs and discussions, splits them into token-budgeted chunks, and pins them to the Context panel with a ready-to-send review prompt. GitHub Enterprise and self-managed GitLab are supported. Configure hosts and tokens in `Settings → Tools → Cliq → Code Review`.

- **No telemetry**  
  No analytics and no background requests. Cliq contacts only the code review hosts you configure, and only when you collect review context.

---

## Installation

### JetBrains Marketplace

1. Open `Settings` → `Plugins` → `Marketplace`
2. Search for `Cliq AI Companion`
3. Click `Install`
4. Restart the IDE

### Supported IDEs

- IntelliJ IDEA
- WebStorm
- PhpStorm
- GoLand
- PyCharm
- RustRover
- RubyMine
- CLion
- DataGrip
- Android Studio (matching platform version)

Requires IntelliJ Platform `2024.3+`.

---

### Manual Installation

1. Download the latest ZIP from Releases
2. Open `Settings` → `Plugins`
3. Select `Install Plugin from Disk...`
4. Restart the IDE

---

## Prerequisites

Cliq works with external CLI agents and does not bundle them.

Install one or both:

- Claude Code (`claude`)
- Gemini CLI (`gemini`)
- Qwen CLI (`qwen`)

Executables can be resolved through `PATH` or configured manually in Settings.

---

## Quick Start

### 1. Configure Agents

Open:

`Settings → Tools → Cliq`

Set commands such as:

```bash
claude
gemini
qwen
```
# B Agent

An Android-native autonomous AI agent: it plans, runs terminal commands, edits
files, drives Git, calls HTTP APIs, controls Android itself through an
accessibility service, and extends itself with tools, skills, plugins and MCP
servers.

**Latest APK (public, no login):**
https://github.com/Albraa776/B-Agent/releases/latest/download/B-Agent-release.apk

## Build

Built entirely in the cloud by GitHub Actions (`.github/workflows/build.yml`):
checkout → JDK 17 → Gradle 8.9 → generate signing keystore → `assembleRelease`
→ publish GitHub Release.

Every push to `master` produces a signed `B-Agent-release.apk` attached to the
`latest` release and uploads it as a workflow artifact.

## Highlights

- **Providers:** OpenAI, Anthropic, Gemini and OpenRouter plus local /
  OpenAI-compatible endpoints, with streaming, tool calling and fallback.
- **Agent runtime:** planning, tool-execution loop, permission gating,
  cancellation, per-task step log and foreground-service notifications.
- **Tools:** terminal (native / Termux / root), filesystem, Git, HTTP,
  Android app + accessibility automation.
- **Extensions:** Markdown skills, JSON plugins, MCP (streamable HTTP) servers.
- **UI:** dark developer workbench in Jetpack Compose (Material 3), RTL-aware.

## Project layout

```
app/src/main/java/com/bagent/app/
  agent/        runtime, planner, context, memory, tasks, sessions
  core/         model, database, settings, network, platform, security, util
  providers/    chat providers + manager
  tools/        tool contract, registry, executor and built-in tools
  mcp/ skills/ plugins/   extension systems
  service/      foreground service, worker, notification receiver
  ui/           Compose theme, activity, view model, screens, components
```

## Requirements

- Android 8.0+ (minSdk 26)
- Android 14+ recommended for foreground-service behaviour

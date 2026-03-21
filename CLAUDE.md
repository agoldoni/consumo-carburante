# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app "Consumo Carburanti" (fuel consumption tracker). Single-module project, currently a starter template with one Activity and no business logic yet.

- **Package**: `it.agoldoni.consumocarburanti`
- **Language**: Java
- **Min SDK**: 24 / **Target SDK**: 34 / **Compile SDK**: 34
- **UI**: ConstraintLayout + Material Design (`Theme.MaterialComponents.DayNight.DarkActionBar`)

## Build Commands

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release APK (unsigned)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk

# Full build (compile + lint + test)
./gradlew build

# Clean
./gradlew clean

# Alternative: use build.sh (supports: debug, release, clean)
./build.sh debug

# list dispositivi in debug
adb devices

# send apk
adb install app/build/outputs/apk/debug/app-debug.apk

```

## Architecture

Single-activity architecture with no patterns (MVVM/MVP) in place yet. All source lives under `app/src/main/java/com/example/consumocarburanti/`. No test directories or dependencies are configured.

## Dependencies

Minimal: `appcompat:1.6.1`, `material:1.11.0`, `constraintlayout:2.1.4`. No networking, database, or DI libraries.

## Build System

- Gradle 8.2 with Android Gradle Plugin 8.2.0
- Java 8 compatibility
- AndroidX + Jetifier enabled
- ProGuard enabled for release builds (default rules only)

## Conventions

- I piani di implementazione e le feature spec vanno sempre salvati nella cartella `docs/prompts/`
- La documentazione di ogni nuova feature va salvata nella cartella `docs/features/`
- Quando l'utente scrive **"nuova feature: xxx"**, devi:
  1. Creare il file `docs/prompts/xxx.md` con la descrizione e il piano della feature
  2. Creare il file `docs/features/xxx.md` con la documentazione della feature
  3. Entrare in modalità pianificazione (plan mode)

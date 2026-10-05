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
# Output: app/build/outputs/apk/debug/consumo_carburanti.apk

# Release APK (unsigned)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/consumo_carburanti.apk

# Full build (compile + lint + test)
./gradlew build

# Clean
./gradlew clean

# Alternative: use build.sh (supports: debug, release, clean)
./build.sh debug

# list dispositivi in debug
adb devices

# send apk
adb install app/build/outputs/apk/debug/consumo_carburanti.apk

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

- Le feature si pianificano e documentano con la skill **`claude-code-feature`**
  (`/claude-code-feature <descrizione>`), anche quando l'utente scrive **"nuova feature: xxx"**.
- Ogni feature ha la cartella `docs/features/NNN-slug/`: `NNN` è un progressivo a 3 cifre
  (massimo prefisso esistente + 1), `slug` è breve, lowercase, con `-` al posto degli spazi (es.
  "Gestione importazione CSV con validazione" → `002-import-csv`). Contiene un file per fase:
  1. **`phase-1-requirements.md`** — obiettivo, scope, user story, criteri di accettazione, rischi,
     stima, milestone
  2. **`phase-2-analysis.md`** — analisi della codebase: file coinvolti, contratti, pattern da
     rispettare, test, rischi aggiornati, prerequisiti
  3. **`phase-3-implementation-plan.md`** — piano di implementazione da approvare; a lavori finiti
     si completa con la sezione **"Esito dell'implementazione"** (cosa è stato fatto, scostamenti
     dal piano, esito delle prove)
- Si passa alla fase successiva solo dopo la conferma esplicita dell'utente.
- Le cartelle senza prefisso numerico (`sync-bluetooth`, `sync-diagnostics`) seguono la
  convenzione precedente (`prompt.md`, `plan.md`, `implementation.md`) e restano com'erano.

## Git

- **Non creare branch senza il permesso esplicito dell'utente.** In questo progetto si lavora
  direttamente su `main`: quando l'utente chiede un commit, committa su `main`. Se pensi che
  serva un branch, chiedilo prima invece di crearlo.

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

- Per ogni feature, creare una cartella `docs/features/{nome_feature}/` contenente 3 file:
  1. **`prompt.md`** — descrizione e specifiche della feature (cosa fare, requisiti, vincoli)
  2. **`plan.md`** — piano di implementazione dettagliato (step, file coinvolti, dipendenze)
  3. **`implementation.md`** — documentazione dell'implementazione (cosa è stato fatto, decisioni prese, note)

- Il nome della cartella feature deve essere breve, lowercase, con `-` al posto degli spazi. Se il nome fornito dall'utente è troppo lungo o contiene caratteri speciali, Claude sceglie un nome sintetico adatto (es. "nuova feature: Gestione importazione CSV con validazione" → `import-csv`).
- Quando l'utente scrive **"nuova feature: xxx"**, devi:
  1. Creare la cartella `docs/features/xxx/`
  2. Entrare in **plan mode** (modello **Opus**) e discutere con l'utente per capire requisiti e specifiche
  3. Al termine della conversazione iniziale, Claude genera `docs/features/xxx/prompt.md` con la sintesi dei requisiti raccolti
  4. Restare in plan mode finché l'utente non conferma che il prompt è completo
  5. Quando l'utente conferma, uscire da plan mode e usare modello **Sonnet** per:
     - Generare `docs/features/xxx/plan.md` (piano di implementazione)
     - Implementare la feature
     - Generare `docs/features/xxx/implementation.md` (documentazione di quanto fatto)

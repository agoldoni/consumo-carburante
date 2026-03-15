# Consumo Carburanti - Piano Funzionalità Principali

## Funzionalità: Lista Rifornimenti con Persistenza

### Struttura Dati (Entity `Rifornimento`)

| Campo | Tipo DB | Note |
|-------|---------|------|
| id | String (PK) | UUID generato automaticamente |
| datetime | long | Epoch millis |
| km | int | Lettura contachilometri (numero intero) |
| qta_benzina | double | Litri riforniti |
| costo | double | Euro spesi |

### Persistenza: Room Database

Best practice Android per dati strutturati anche con volumi contenuti. Vantaggi: type safety, verifica SQL a compile-time, integrazione lifecycle.

### Dipendenze da Aggiungere

```groovy
implementation 'androidx.room:room-runtime:2.6.1'
annotationProcessor 'androidx.room:room-compiler:2.6.1'
implementation 'androidx.recyclerview:recyclerview:1.3.2'
```

### File da Creare/Modificare

#### Nuovi File Java
1. **`Rifornimento.java`** — Entity Room (`@Entity(tableName = "rifornimenti")`)
2. **`RifornimentoDao.java`** — DAO con `getAll()` ORDER BY datetime DESC, `insert()`, `delete()`, `update()`
3. **`AppDatabase.java`** — Database singleton (`consumo_carburanti_db`, version 1)
4. **`RifornimentoAdapter.java`** — Adapter RecyclerView con formattazione locale italiana + dato derivato km/l

#### Layout XML
5. **`activity_main.xml`** — Riscrittura: `CoordinatorLayout` con RecyclerView + TextView empty state + FAB
6. **`item_rifornimento.xml`** — `MaterialCardView`: data/ora (sx), costo EUR (dx), km e litri, consumo km/l (derivato)
7. **`dialog_add_rifornimento.xml`** — 4 campi `TextInputLayout`: data/ora (DatePicker+TimePicker), km, litri, costo

#### File Modificati
8. **`MainActivity.java`** — Logica principale: `ExecutorService` per DB, `loadData()`, `showAddDialog()` con validazione
9. **`strings.xml`** — Stringhe UI in italiano
10. **`app/build.gradle`** — Dipendenze Room e RecyclerView

### Ordine di Implementazione

1. `app/build.gradle` (dipendenze)
2. `Rifornimento.java` (entity)
3. `RifornimentoDao.java` (DAO)
4. `AppDatabase.java` (database) → build di verifica
5. `strings.xml` (stringhe)
6. `item_rifornimento.xml` (layout item)
7. `dialog_add_rifornimento.xml` (layout dialog)
8. `activity_main.xml` (layout main)
9. `RifornimentoAdapter.java` (adapter)
10. `MainActivity.java` (logica) → build finale

### Note Tecniche

- **Thread safety**: tutte le query Room via `ExecutorService` + `runOnUiThread()` per aggiornare UI
- **Java 8 compat**: datetime come `long` (epoch millis), formattazione con `SimpleDateFormat`/`Calendar`
- **Locale italiano**: parsing input con gestione `,`/`.` come separatore decimale
- **UI**: `notifyDataSetChanged()` sufficiente per volumi contenuti
- **Visualizzazione**: lista in ordine decrescente (più recente in alto)
- **Dato derivato km/l**: calcolato come `(km_attuale - km_precedente) / qta_benzina_attuale`, non salvato in DB. La voce più vecchia (senza precedente) non mostra questo dato.
- **Input data/ora**: pre-compilato con data corrente, modificabile tramite DatePicker + TimePicker

### Verifica

```bash
./gradlew assembleDebug
```
Test manuale: aggiungere 2-3 rifornimenti, verificare ordinamento decrescente, chiudere e riaprire app per verificare persistenza.

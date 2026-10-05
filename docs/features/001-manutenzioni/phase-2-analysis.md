# Manutenzioni — Fase 2: analisi tecnica

> Base: [phase-1-requirements.md](phase-1-requirements.md) (approvato il 2026-10-05).
> Analisi sul codice di `main` al commit `fcb477e`.

## Quadro della codebase

App Android monomodulo in Java 8: `minSdk 24`, Room 2.6.1, Gson 2.10.1, HiveMQ MQTT client 1.3.3.
Tutte le classi stanno nel package `it.agoldoni.consumocarburanti`, senza sottopackage né pattern
architetturali: ogni Activity accede direttamente ai DAO tramite un `ExecutorService`
single-thread. Non ci sono test né dipendenze di test.

| Area | File esistenti rilevanti |
|---|---|
| Dati | `AppDatabase` (v5, `exportSchema = false`), `Veicolo`/`VeicoloDao`, `Rifornimento`/`RifornimentoDao` |
| UI | `MainActivity` (rifornimenti, drawer, spinner auto, CSV), `VeicoloActivity`, `RifornimentoAdapter` |
| Merge | `SyncMerger` (last-write-wins, unico punto per MQTT e Bluetooth) |
| MQTT | `MqttSyncManager`, `MqttConfig`, `SyncLog` |
| Bluetooth | `BluetoothSyncManager`, `BtMessages`, `BluetoothSyncActivity`, `BtOfferAdapter` |

Il CLAUDE.md del progetto descrive ancora l'app come "starter template" senza logica: è
superato, ma non è oggetto di questa feature.

---

## A. File coinvolti

Percorsi relativi a `app/src/main/`.

### Nuovi

| # | File | Motivazione |
|---|---|---|
| 1 | `java/.../Manutenzione.java` | Entity Room `manutenzioni`, stessa struttura di `Rifornimento` (UUID, `updatedAt`, FK `veicolo_id` CASCADE) |
| 2 | `java/.../TipoManutenzione.java` | Enum `ALTRO`, `TAGLIANDO`, `REVISIONE` con codice persistito ed etichetta (`@StringRes`); `fromCodice()` con ripiego su `ALTRO` |
| 3 | `java/.../ManutenzioneDao.java` | CRUD, elenco per auto, lettura per più auto (Bluetooth), conteggio per auto (offerta), `reassignVeicolo` |
| 4 | `java/.../ManutenzioneActivity.java` | Schermata elenco + spinner auto + FAB + dialog di aggiunta/modifica + eliminazione |
| 5 | `java/.../ManutenzioneAdapter.java` | Adapter RecyclerView, ricalcato su `RifornimentoAdapter` |
| 6 | `res/layout/activity_manutenzione.xml` | Toolbar + Spinner in `AppBarLayout`, RecyclerView, empty view, FAB (come `activity_main.xml`) |
| 7 | `res/layout/item_manutenzione.xml` | `MaterialCardView`: data e costo, tipo e km, descrizione, pulsanti modifica/elimina |
| 8 | `res/layout/dialog_add_manutenzione.xml` | Campi data, tipo (menu a tendina), descrizione, km, costo |
| 9 | `res/drawable/ic_manutenzione.xml` | Icona vettoriale (chiave inglese) per la voce del drawer |

### Modificati

| # | File | Modifica |
|---|---|---|
| 10 | `java/.../AppDatabase.java` | Entity `Manutenzione`, `version = 6`, `MIGRATION_5_6`, `manutenzioneDao()`, registrazione in `addMigrations` |
| 11 | `java/.../SyncMerger.java` | `applyManutenzione`, `deleteManutenzione`; `adottaIdVeicolo` sposta anche le manutenzioni; nuovo esito `SCARTATO_INVALIDO` |
| 12 | `java/.../MqttSyncManager.java` | Sottoscrizione `manutenzioni/#`, `handleManutenzione`, `publishManutenzione`, `publishDeleteManutenzione`, manutenzioni nella sync completa |
| 13 | `java/.../BtMessages.java` | `PROTOCOL_VERSION` 1 → 2; `DataPayload.manutenzioni`; `VeicoloOfferto.nManutenzioni` |
| 14 | `java/.../BluetoothSyncManager.java` | Contatori nel `Riepilogo`; manutenzioni in `raccogli`, `applica`, `costruisciOfferta`, `riallineaMqtt`, `descrivi` |
| 15 | `java/.../BluetoothSyncActivity.java` | `onConclusa`: riepilogo con le manutenzioni |
| 16 | `java/.../BtOfferAdapter.java` | `descrivi`: numero di manutenzioni accanto ai rifornimenti |
| 17 | `java/.../MainActivity.java` | Voce drawer che apre `ManutenzioneActivity` passando l'auto selezionata; listener dati MQTT registrato in `onResume` invece che in `onCreate` (vedi rischio E3) |
| 18 | `res/menu/nav_menu.xml` | Voce `nav_manutenzioni`, dopo `nav_auto` |
| 19 | `AndroidManifest.xml` | Registrazione di `ManutenzioneActivity` con `parentActivityName=".MainActivity"` |
| 20 | `res/values/strings.xml` | Stringhe nuove (vedi B.7) e aggiornamento di `conferma_elimina_veicolo`, `bt_seleziona_auto`, `bt_riepilogo_corpo` |

### Esplicitamente non toccati

- `Rifornimento*`, `Veicolo`, `VeicoloDao`: le manutenzioni sono una tabella figlia separata.
- `VeicoloActivity`: l'eliminazione di un'auto si propaga da sola con la `ON DELETE CASCADE`,
  anche sugli altri telefoni (il messaggio di eliminazione dell'auto innesca la stessa cascade).
  Cambia solo il testo di conferma (stringa).
- Esportazione e importazione CSV in `MainActivity` (fuori scope).
- `SyncLog`, `SyncStatusActivity`: le categorie esistenti (`CAT_RECV`, `CAT_PUB`, `CAT_BT`)
  bastano.
- `app/build.gradle`: nessuna nuova dipendenza.

---

## B. Contratti e interfacce

### B.1 Schema DB (versione 5 → 6, additivo)

Entity:

```java
@Entity(tableName = "manutenzioni",
        indices = @Index("veicolo_id"),
        foreignKeys = @ForeignKey(entity = Veicolo.class, parentColumns = "id",
                childColumns = "veicolo_id", onDelete = ForeignKey.CASCADE))
public class Manutenzione {
    @PrimaryKey @NonNull private String id;          // UUID
    @NonNull private String data;                    // "yyyy-MM-dd"
    @NonNull private String tipo;                    // codice TipoManutenzione
    private String descrizione;                      // null se vuota
    private Integer km;                              // null = non indicati
    private double costo;
    @ColumnInfo(name = "veicolo_id") private String veicoloId;
    private long updatedAt;
}
```

SQL della migrazione, nella forma che Room genera per le tabelle esistenti
(`app/build/generated/ap_generated_sources/debug/out/.../AppDatabase_Impl.java`, riga 38):

```sql
CREATE TABLE IF NOT EXISTS `manutenzioni` (`id` TEXT NOT NULL, `data` TEXT NOT NULL,
  `tipo` TEXT NOT NULL, `descrizione` TEXT, `km` INTEGER, `costo` REAL NOT NULL,
  `veicolo_id` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`),
  FOREIGN KEY(`veicolo_id`) REFERENCES `veicoli`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
CREATE INDEX IF NOT EXISTS `index_manutenzioni_veicolo_id` ON `manutenzioni` (`veicolo_id`)
```

Lo SQL definitivo va **copiato dal file generato** dopo la prima build con la nuova entity, non
riscritto a mano (rischio E1).

**Scelte di rappresentazione**
- `data` come stringa ISO `yyyy-MM-dd`: non dipende dal fuso orario, l'ordine lessicografico
  coincide con quello cronologico e il valore resta leggibile anche nel JSON di sync. Con
  `minSdk 24` e senza desugaring in `app/build.gradle`, `java.time.LocalDate` non è disponibile;
  la conversione si fa con `Calendar` e `String.format(Locale.US, "%04d-%02d-%02d", …)`.
- `tipo` come stringa con il nome della costante (`"TAGLIANDO"`), non come ordinale: è robusto a
  riordini e aggiunte futuri ed è leggibile nel log di sync.
- `km` come `Integer` nullable: distingue "non indicati" da `0`, a differenza di
  `Rifornimento.km` (`int`, obbligatorio).

### B.2 DAO

```java
@Dao
public interface ManutenzioneDao {
    @Query("SELECT * FROM manutenzioni ORDER BY data DESC, updatedAt DESC")
    List<Manutenzione> getAll();                                  // sync completa MQTT
    @Query("SELECT * FROM manutenzioni WHERE veicolo_id = :veicoloId ORDER BY data DESC, updatedAt DESC")
    List<Manutenzione> getByVeicolo(String veicoloId);            // schermata
    @Query("SELECT * FROM manutenzioni WHERE id = :id")
    Manutenzione getById(String id);                              // merge
    @Query("SELECT * FROM manutenzioni WHERE veicolo_id IN (:veicoloIds)")
    List<Manutenzione> getByVeicoli(List<String> veicoloIds);     // payload Bluetooth
    @Query("SELECT veicolo_id AS veicoloId, COUNT(*) AS conteggio FROM manutenzioni " +
           "WHERE veicolo_id IN (:veicoloIds) GROUP BY veicolo_id")
    List<Conteggio> countByVeicoli(List<String> veicoloIds);      // offerta Bluetooth
    @Query("UPDATE manutenzioni SET veicolo_id = :nuovoId, updatedAt = :quando WHERE veicolo_id = :vecchioId")
    int reassignVeicolo(String vecchioId, String nuovoId, long quando);
    @Insert void insert(Manutenzione m);
    @Update void update(Manutenzione m);
    @Delete void delete(Manutenzione m);

    class Conteggio { public String veicoloId; public int conteggio; }
}
```

Il secondo criterio `updatedAt DESC` rende stabile l'ordine di più interventi nello stesso giorno.

### B.3 `SyncMerger`

| Metodo | Contratto |
|---|---|
| `Esito applyManutenzione(Manutenzione remote)` | `SCARTATO_INVALIDO` se `id`, `data` o `tipo` sono null; `SCARTATO_FK` se l'auto non esiste; poi `INSERITO` / `AGGIORNATO` / `GIA_ALLINEATO` con la regola di `applyRifornimento` (`>` stretto su `updatedAt`) |
| `Manutenzione deleteManutenzione(String id)` | Come `deleteRifornimento`: restituisce il record eliminato o `null` |
| `adottaIdVeicolo(String, Veicolo)` | Nella stessa transazione ripunta anche le manutenzioni **prima** di eliminare la vecchia auto. Il tipo restituito passa da `int` a una piccola classe `Spostamento { int rifornimenti; int manutenzioni; }`; l'unico chiamante è `BluetoothSyncManager.applica` (riga 616) |
| `enum Esito` | Nuovo valore `SCARTATO_INVALIDO`. Gli `switch` esistenti hanno tutti un `default`, quindi restano corretti |

### B.4 MQTT (additivo, nessun breaking change)

- Topic: `sync/{groupId}/manutenzioni/{id}`, QoS 1, retained, come gli altri tipi.
- Payload: il JSON Gson dell'entity (nomi dei campi Java, quindi `veicoloId`, non `veicolo_id`);
  Gson omette i campi `null`, per esempio `km`:
  ```json
  {"id":"5b1c…","data":"2026-09-14","tipo":"TAGLIANDO","descrizione":"Olio e filtri",
   "km":84250,"costo":235.0,"veicoloId":"d3a0…","updatedAt":1789000000000}
  ```
- Eliminazione: payload vuoto sullo stesso topic (il broker rimuove anche il retained).
- API pubblica nuova: `publishManutenzione(Manutenzione)`, `publishDeleteManutenzione(String id)`.
- Le installazioni precedenti sottoscrivono solo `veicoli/#` e `rifornimenti/#`
  (`MqttSyncManager.subscribeToTopics`, righe 270-271): il nuovo topic non le raggiunge.

### B.5 Protocollo Bluetooth v2 (breaking, intenzionale: decisione D8)

| Messaggio | Modifica |
|---|---|
| `PROTOCOL_VERSION` | `1` → `2`. Il controllo esistente (`BluetoothSyncManager`, righe 424-430) rifiuta la sessione fra versioni diverse e mostra `bt_err_versione` |
| `DataPayload` | Nuovo campo `List<Manutenzione> manutenzioni`; costruttore a tre argomenti |
| `VeicoloOfferto` | Nuovo campo `int nManutenzioni`, mostrato nell'offerta |
| `Done` | Invariato (i contatori sono informativi e non vengono letti) |

`Riepilogo` acquista `manutenzioniNuove`, `manutenzioniAggiornate`, `manutenzioniScartate` e
`manutenzioniInviate`.

### B.6 UI e navigazione

- `ManutenzioneActivity.EXTRA_VEICOLO_ID` (String): l'auto da preselezionare. È necessario perché
  `MainActivity` salva l'auto scelta con `getPreferences()`, che sono private dell'activity e non
  leggibili da un'altra (righe 186-189, 262-263). Cambiare auto nella schermata manutenzioni non
  modifica la scelta della schermata principale.
- Il menu a tendina del tipo usa `TextInputLayout` con lo stile
  `Widget.MaterialComponents.TextInputLayout.OutlinedBox.ExposedDropdownMenu` e un
  `AutoCompleteTextView` non editabile (Material 1.11), nell'ordine Altro, Tagliando, Revisione,
  senza preselezione (D4).

### B.7 Stringhe

- **Nuove**: `menu_manutenzioni`, `nuova_manutenzione`, `modifica_manutenzione`,
  `conferma_elimina_manutenzione`, `nessuna_manutenzione`, `data`, `tipo_manutenzione`,
  `tipo_altro`, `tipo_tagliando`, `tipo_revisione`, `descrizione`, `chilometri_facoltativi`,
  `descrizione_obbligatoria_altro`, `mqtt_inviata_manutenzione`, `mqtt_ricevuta_manutenzione`,
  plurale `bt_n_manutenzioni`.
- **Modificate**:
  - `conferma_elimina_veicolo` deve citare anche le manutenzioni;
  - `bt_seleziona_auto` ("Riceverai indietro i suoi rifornimenti…") deve dire "rifornimenti e
    manutenzioni";
  - `bt_riepilogo_corpo` passa da 5 a 7 argomenti. Va cambiato insieme a `onConclusa`
    (`BluetoothSyncActivity`, riga 445), altrimenti va in crash con
    `MissingFormatArgumentException`.

---

## C. Pattern da rispettare

**Entity e DAO**
- Chiave primaria `String` con UUID assegnato nel costruttore, `updatedAt` impostato nel
  costruttore e a ogni modifica (`setUpdatedAt(System.currentTimeMillis())` prima di `update`).
- Getter e setter privati per ogni campo (Gson e Room leggono i campi). `@ColumnInfo` in
  snake_case solo per `veicolo_id`, come in `Rifornimento`.
- DAO sincroni, chiamati dall'`ExecutorService` single-thread dell'activity; la UI si aggiorna
  con `runOnUiThread`.
- Migrazioni come `static final Migration MIGRATION_X_Y` con SQL grezzo, aggiunte a
  `addMigrations(...)`.

**Activity e dialog** (riferimento: `VeicoloActivity`, `MainActivity.showAddDialog`)
- `Toolbar` + `setSupportActionBar`, freccia indietro con `onSupportNavigateUp() { finish(); }`,
  `executor.shutdown()` in `onDestroy`.
- Layout `CoordinatorLayout` con RecyclerView (`paddingBottom="80dp"`, sotto il FAB), empty view
  centrata e FAB `@android:drawable/ic_input_add`.
- Dialog: `AlertDialog` con view personalizzata. Il listener del pulsante positivo si imposta
  **dopo** `show()`, così la validazione può lasciare il dialog aperto. Errori sul campo con
  `TextInputLayout.setError(getString(R.string.campo_obbligatorio | valore_non_valido))`; testo
  letto con l'helper `getText()` (trim); decimali con `replace(',', '.')`.
- Data non editabile da tastiera: `TextInputEditText` con `focusable=false`, `clickable=true` e
  `DatePickerDialog` al click (senza il `TimePickerDialog` dei rifornimenti).
- Dopo ogni scrittura su DB, nello stesso task dell'executor:
  `MqttSyncManager.getInstance(this).publishX(...)`.
- Eliminazione: `AlertDialog` di conferma con `R.string.elimina` / `R.string.annulla`.
- Senza auto: FAB disabilitato e testo `aggiungi_veicolo_prima` nella empty view
  (`MainActivity.loadVeicoli`).

**Adapter**
- Interfaccia `OnManutenzioneActionListener { onEdit; onDelete; }`, `setData()` con
  `notifyDataSetChanged()` (volumi piccoli).
- Formati: `Locale.ITALY`, costo `"€ %.2f"`, km `"%,d"`, data visualizzata `dd/MM/yyyy`.
  Le etichette fisse (es. "Km:") stanno in `strings.xml`, non nel codice.

**Sync**
- La logica di merge sta solo in `SyncMerger`; MQTT e Bluetooth la chiamano e basta.
- MQTT: `syncLog.recordReceived()` + `syncLog.info(CAT_RECV, …)` + toast + `notifyListener()`
  per inserimenti e aggiornamenti; `countUpToDate()` per i record già allineati; `warn` per gli
  scarti FK, `error` per i payload illeggibili.
- Bluetooth: tutte le scritture del merge dentro l'unico `db.runInTransaction` di `applica`;
  scarto silenzioso (conteggiato) dei record di auto non concordate.

**Stile**
- Commenti e javadoc in italiano, che spiegano il *perché*. Nei file recenti (`SyncMerger`,
  `BluetoothSyncManager`) gli accenti sono scritti come apostrofo (`e'`, `piu'`): si segue lo
  stile del file che si modifica.
- Tutti i testi visibili in `strings.xml`, in italiano.

---

## D. Test da creare o aggiornare

### Stato attuale

Non esistono `app/src/test` né `app/src/androidTest`, né dipendenze di test in
`app/build.gradle`. `exportSchema = false` impedisce l'uso di `MigrationTestHelper`, che
richiede lo schema JSON esportato.

### Automatici

| Tipo | Oggetto | Proposta |
|---|---|---|
| Unit (JVM) | `TipoManutenzione.fromCodice`, conversioni data ISO ↔ `dd/MM/yyyy` | **Facoltativo**: richiede `testImplementation 'junit:junit:4.13.2'` e `app/src/test/`; copre la parte meno rischiosa |
| Instrumented | Migrazione 5 → 6 | **Non previsto**: servono `exportSchema = true`, `room.schemaLocation`, `room-testing` e `androidTest`. È un lavoro di infrastruttura a sé, utile a tutte le migrazioni future |

Raccomandazione: nessuna infrastruttura di test in questa feature; verifica manuale strutturata
come sotto (vedi "Domande aperte" in Fase 3).

### Manuali (dispositivo)

Dispositivi disponibili:
- **Redmi Note 13 Pro**, Android 16 (`m7mvnndmhqugnbr8`, USB). Ha installate sia la build release
  (`it.agoldoni.consumocarburanti`) sia la debug (`.debug`). Il database della debug è alla
  **versione 5 con 3 auto e 11 rifornimenti**: è il banco di prova per la migrazione reale.
  `install-all.sh` usa `adb install -r`, che conserva i dati.
- **REDMI Note 15**, Android 15, HyperOS 2 (`QGNVUCRS85HAUKBI`, USB), Bluetooth acceso. Ha solo
  la build **release** (installata il 2026-08-17, protocollo Bluetooth v1); la debug va
  installata. È il secondo telefono per MQTT e Bluetooth.
- **Emulatore** (`Emulator_x86_64` o `Redmi_Note_13_Pro_5G`, avviato con finestra): solo di
  riserva per MQTT. L'emulatore non ha RFCOMM e non serve per il Bluetooth.

**Si installano solo build debug.** Le release presenti sui due telefoni non vengono
aggiornate né usate nelle prove, quindi non serve una copia dei loro dati.

T12 (v1 contro v2) richiede una debug con il protocollo v1: si compila da `905e6c2` (ultimo
commit prima della feature) in un worktree separato e la si installa su un telefono, mentre
l'altro ha la nuova debug. Al termine, entrambi passano alla nuova debug.

| ID | Caso | Esito atteso |
|---|---|---|
| T1 | Installare la nuova debug sopra la v5 del telefono e aprirla | Nessun crash; 3 auto e 11 rifornimenti invariati; `PRAGMA user_version` = 6; tabella `manutenzioni` vuota |
| T2 | Inserire una manutenzione per ciascun tipo, con e senza km | Elenco ordinato per data decrescente; km mostrati solo se presenti |
| T3 | Validazione | Tipo non scelto → errore; "Altro" senza descrizione → errore; costo vuoto/negativo → errore, `0` accettato; km non intero o negativo → errore, km vuoti accettati |
| T4 | Modifica ed eliminazione | Dialog precompilato; eliminazione dopo conferma |
| T5 | Cambio auto nella schermata; nessuna auto | Elenco filtrato; senza auto FAB disabilitato e messaggio |
| T6 | Eliminare un'auto con manutenzioni | Spariscono anche le manutenzioni |
| T7 | MQTT telefono ↔ emulatore, stesso gruppo | Inserimento, modifica ed eliminazione propagati; un'auto creata a mano su un solo lato → manutenzione scartata con voce nel log |
| T8 | MQTT con "Riconnetti ora" | La sync completa ripubblica anche le manutenzioni (contatore nel log) |
| T9 | Schermata manutenzioni aperta mentre arriva un dato MQTT | L'elenco si aggiorna; tornando alla principale, anche questa si aggiorna ai messaggi successivi |
| T10 | Bluetooth fra due telefoni v2 | Stesso insieme di manutenzioni per le auto concordate; riepilogo con i contatori; offerta con "N manutenzioni" |
| T11 | Bluetooth con collegamento di un'auto locale a una remota | Le manutenzioni dell'auto locale restano, ora sull'id remoto |
| T12 | Bluetooth fra v1 e v2 | Sessione rifiutata con "versioni diverse del protocollo" su entrambi |
| T13 | Regressione | Rifornimenti: inserimento, consumi, CSV, sync MQTT e Bluetooth invariati |

T7–T12 si eseguono fra i due telefoni fisici.

---

## E. Rischi tecnici aggiornati

| # | Rischio | Evidenza | Mitigazione | Gravità |
|---|---|---|---|---|
| E1 | Crash all'avvio dopo l'aggiornamento se lo SQL della migrazione differisce dallo schema atteso | `AppDatabase.java:12` `exportSchema = false`; Room confronta colonne, nullabilità, FK e indici in `onValidateSchema` (`AppDatabase_Impl`, righe 110-128) | Copiare lo SQL da `AppDatabase_Impl.createAllTables` generato; T1 sulla debug con dati reali del Redmi Note 13 Pro | Alta |
| E2 | Perdita delle manutenzioni collegando un'auto via Bluetooth | `SyncMerger.adottaIdVeicolo`: ripunta solo i rifornimenti (riga 130) e poi elimina la vecchia auto (riga 134); con la FK in CASCADE le manutenzioni rimaste sulla vecchia auto verrebbero cancellate | `manutenzioneDao().reassignVeicolo` nella stessa transazione, prima della `delete`; T11 | Alta |
| E3 | La schermata principale smette di aggiornarsi sui dati MQTT ricevuti | `MqttSyncManager` tiene **un solo** listener dati (righe 87-89); `MainActivity` lo imposta solo in `onCreate` (riga 142). Se `ManutenzioneActivity` lo sovrascrive, al ritorno resta registrato il suo, riferito a un'activity già chiusa | Entrambe le activity lo registrano in `onResume`; T9 | Media |
| E4 | Un payload senza `data` o `tipo` viola i `NOT NULL` | Gson lascia `null` i campi mancanti. Su MQTT l'eccezione è catturata in `handleIncoming` (riga 305), ma in Bluetooth avviene dentro `runInTransaction` di `applica` (riga 628) e **annullerebbe l'intera sessione** | Controllo in `SyncMerger.applyManutenzione` → `SCARTATO_INVALIDO` prima di scrivere | Media |
| E5 | Data che slitta di un giorno fra telefoni | Rischio eliminato rappresentando la data come stringa ISO (B.1) | — | Risolto |
| E6 | Tipo sconosciuto ricevuto da una versione futura | `tipo` è un codice stringa | `fromCodice` ripiega su `ALTRO` solo per la visualizzazione; il valore salvato non viene riscritto | Bassa |
| E7 | Sessioni Bluetooth bloccate finché entrambi i telefoni non sono aggiornati | Effetto voluto di D8; il messaggio esiste già (`bt_err_versione`) | Aggiornare insieme i due telefoni | Bassa |
| E8 | Messaggi retained orfani sul broker dopo l'eliminazione di un'auto | Già oggi `publishDeleteVeicolo` non pulisce i retained dei rifornimenti, che a ogni riconnessione finiscono scartati per FK. Le manutenzioni raddoppiano gli avvisi nel log | Fuori scope: comportamento preesistente | Bassa |
| E9 | Crash del riepilogo Bluetooth | `bt_riepilogo_corpo` cambia numero di argomenti (B.7) | Modifica contestuale di stringa e `onConclusa`; T10 | Bassa |

---

## F. Prerequisiti e task bloccanti

Nessun refactoring bloccante: la feature si innesta sui pattern esistenti. Prima di iniziare o
durante i lavori servono:

1. ~~**Modifiche non committate nel working tree**~~ estranee alla feature (rinomina dell'APK in
   `consumo_carburanti.apk`): ✅ committate a parte in `905e6c2`.
2. **Prima build con la nuova entity**, per ricavare lo SQL della migrazione da
   `AppDatabase_Impl` (E1). Va fatta prima di scrivere `MIGRATION_5_6`.
3. **Secondo dispositivo per MQTT**: il REDMI Note 15, con la build debug configurata con lo
   stesso broker e `groupId` del Redmi Note 13 Pro.
4. ✅ **Secondo telefono fisico per il Bluetooth** (T10–T12): REDMI Note 15 collegato e
   autorizzato in adb. Resta da installare la debug (`install-all.sh` installa su tutti i
   dispositivi collegati).
5. Il riordino del listener MQTT in `MainActivity` (E3) è un piccolo prerequisito tecnico, ma
   rientra nella feature (milestone UI).

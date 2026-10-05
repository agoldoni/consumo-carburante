# Manutenzioni — Implementation Plan

**Stato:** Implementato e provato il 2026-10-05 — vedi [Esito dell'implementazione](#esito-dellimplementazione)
**Autore:** Alberto Goldoni (redatto con Claude Code)
**Data:** 2026-10-05
**Versione:** 1.2

Documenti di base: [Fase 1 — requisiti](phase-1-requirements.md) ·
[Fase 2 — analisi tecnica](phase-2-analysis.md)

---

## 1. Executive Summary

L'app Consumo Carburanti oggi registra solo i rifornimenti. Questa feature aggiunge lo storico
degli **interventi di manutenzione** di ogni auto: data, tipo (Altro, Tagliando, Revisione),
descrizione, km facoltativi e costo. Gli interventi si consultano e si modificano da una nuova
schermata e si sincronizzano fra i telefoni che condividono l'auto, via MQTT e via Bluetooth, come
i rifornimenti. L'impegno stimato è di **3,5 giorni/uomo**; la feature è pronta quando supera le
prove manuali sui due telefoni di test, usando solo build debug.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** tagliandi, revisioni e riparazioni vanno annotati fuori dall'app, quindi
  non si sa a colpo d'occhio quando è stato fatto l'ultimo intervento, quando scade la revisione
  né quanto è costata la manutenzione di un'auto. Chi condivide un'auto non ha uno storico comune.
- **Metriche di successo:**
  - [ ] Aggiornamento senza crash e senza perdita di dati sul telefono con dati reali (debug del
        Redmi Note 13 Pro: 3 auto, 11 rifornimenti).
  - [ ] Dopo una sync (MQTT o Bluetooth) i due telefoni mostrano lo stesso elenco di interventi
        per le auto condivise, nel 100% delle prove T7–T11.
  - [ ] Nessuna voce `ERROR` nella diagnostica sync durante le prove, a parte quelle provocate
        apposta.
  - [ ] Nessuna regressione su rifornimenti, consumi, CSV e sync esistente (T13).
- **Legame con obiettivi di prodotto:** app personale senza OKR. Allarga l'app da "consumo
  carburante" a "spese dell'auto", base per i promemoria di scadenza (fuori scope).

---

## 3. Scope

### Incluso
- Tabella `manutenzioni` (data ISO `yyyy-MM-dd`, tipo, descrizione, km facoltativi, costo) con
  migrazione Room 5 → 6.
- Schermata "Manutenzioni" dal menu laterale: elenco per auto, aggiunta, modifica, eliminazione.
- Sync MQTT: pubblicazione, ricezione, eliminazione, sync completa.
- Sync Bluetooth con protocollo v2: manutenzioni nel payload, nell'offerta e nel riepilogo.
- Integrità: cascade all'eliminazione dell'auto; manutenzioni conservate quando un'auto si
  collega a quella di un altro telefono.
- Versione dell'app: `versionName` 1.0 → **1.1**, `versionCode` 1 → **2**.

### Escluso (out of scope)
- Promemoria e scadenze: richiedono notifiche e pianificazione; questa feature ne crea solo i dati.
- Tipi personalizzabili: i tre tipi fissi coprono il caso d'uso; il codice a stringa permette di
  aggiungerne in futuro.
- Esportazione e importazione CSV delle manutenzioni (D7).
- Allegati (foto, PDF): richiedono storage di file e un trasporto diverso nella sync.
- Statistiche combinate carburante + manutenzione e totale speso in testa all'elenco (D6).
- Pulizia dei messaggi retained orfani sul broker dopo l'eliminazione di un'auto: problema
  preesistente che riguarda anche i rifornimenti.
- Infrastruttura di test automatici, in particolare i test di migrazione Room (vedi Domande
  aperte).
- Pubblicazione di una build release: questo piano arriva fino alla debug provata.

### Decisioni aperte

Tutte le decisioni di prodotto (D1–D8) sono state approvate il 2026-10-05: vedi
[Fase 1](phase-1-requirements.md#decisioni-approvate). Restano solo le domande di processo in
fondo al documento.

---

## 4. User Stories e criteri di accettazione

### US-001 · Registrare un intervento
**Priorità:** Must Have

Come proprietario dell'auto voglio registrare un intervento con data, tipo, descrizione, km e costo,
per tenere traccia di cosa è stato fatto e quanto è costato.

**Criteri di accettazione:**
- [ ] La voce "Manutenzioni" del menu laterale apre la schermata sull'auto selezionata nella
      principale.
- [ ] Il "+" apre un dialog con data (oggi, modificabile con DatePicker, senza ora), tipo (nessuno
      preselezionato), descrizione, km, costo.
- [ ] Il tipo è obbligatorio; la descrizione è obbligatoria solo per "Altro".
- [ ] Il costo è obbligatorio, accetta `,` e `.`, è ≥ 0 (`0` ammesso).
- [ ] I km sono facoltativi; se compilati, sono un intero ≥ 0.
- [ ] Senza auto registrate il FAB è disabilitato e un messaggio rimanda a "Le mie auto".

### US-002 · Consultare lo storico
**Priorità:** Must Have

Come proprietario dell'auto voglio vedere gli interventi dell'auto selezionata, dal più recente,
per sapere quando ho fatto l'ultimo tagliando o l'ultima revisione.

**Criteri di accettazione:**
- [ ] Elenco filtrato per auto, ordinato per data decrescente (a parità di data, l'ultimo
      modificato in alto).
- [ ] Ogni voce mostra data `dd/MM/yyyy`, tipo, descrizione, costo `€ 0,00` e, solo se presenti,
      i km con separatore delle migliaia.
- [ ] Elenco vuoto → messaggio; cambio auto dallo spinner → elenco aggiornato.

### US-003 · Correggere un intervento
**Priorità:** Must Have

Come utente voglio modificare o eliminare un intervento, per correggere un errore di inserimento.

**Criteri di accettazione:**
- [ ] La modifica apre il dialog precompilato e aggiorna `updatedAt`.
- [ ] L'eliminazione chiede conferma.
- [ ] Eliminare un'auto elimina anche i suoi interventi, e il testo di conferma lo dice.

### US-004 · Condividere lo storico
**Priorità:** Must Have

Come persona che condivide un'auto voglio che gli interventi compaiano su entrambi i telefoni, per
avere un unico storico.

**Criteri di accettazione:**
- [ ] MQTT: inserimento, modifica ed eliminazione arrivano all'altro telefono entro pochi secondi;
      la sync completa li ripubblica.
- [ ] La schermata aperta si aggiorna all'arrivo di un dato MQTT; la principale continua ad
      aggiornarsi dopo esserci tornati.
- [ ] Bluetooth: stesso insieme di interventi per le auto concordate; offerta con "N manutenzioni";
      riepilogo con i contatori.
- [ ] Modifiche concorrenti: vince la più recente.
- [ ] Interventi di auto assenti o con dati mancanti scartati con voce nel log; una sessione
      Bluetooth non fallisce per un record malformato.
- [ ] Collegare un'auto locale a una remota conserva gli interventi.
- [ ] Bluetooth fra v1 e v2: sessione rifiutata con il messaggio sulle versioni diverse.

### US-005 · Aggiornare senza perdere dati
**Priorità:** Must Have

Come utente che aggiorna l'app voglio ritrovare intatti auto e rifornimenti, per non perdere lo
storico.

**Criteri di accettazione:**
- [ ] Installazione sopra la versione con DB 5: nessun crash, dati invariati, DB alla versione 6.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
                    ┌──────────────── UI ────────────────┐
  MainActivity ──(drawer, EXTRA_VEICOLO_ID)──▶ ManutenzioneActivity
       │                                       │  ManutenzioneAdapter
       │                                       │  dialog_add_manutenzione
       ▼                                       ▼
  RifornimentoDao                       ManutenzioneDao ──▶ tabella manutenzioni
  VeicoloDao                                   ▲             (FK veicolo_id CASCADE)
       ▲                                       │
       └────────────── SyncMerger ─────────────┘   last-write-wins su updatedAt
                     ▲            ▲
        MqttSyncManager          BluetoothSyncManager
   sync/{g}/manutenzioni/{id}    DataPayload.manutenzioni (protocollo v2)
```

Dopo ogni scrittura locale, l'activity pubblica su MQTT
(`publishManutenzione` / `publishDeleteManutenzione`). I dati in arrivo da MQTT e Bluetooth passano
solo da `SyncMerger`.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `manutenzioni` | Nuovo | `id` TEXT PK, `data` TEXT NOT NULL (`yyyy-MM-dd`), `tipo` TEXT NOT NULL, `descrizione` TEXT, `km` INTEGER (null = non indicati), `costo` REAL NOT NULL, `veicolo_id` TEXT FK → `veicoli.id` ON DELETE CASCADE, `updatedAt` INTEGER NOT NULL; indice su `veicolo_id` |
| `AppDatabase` | Modifica | `version` 5 → 6, `MIGRATION_5_6` con lo SQL copiato da `AppDatabase_Impl` generato |
| `TipoManutenzione` | Nuovo (enum) | `ALTRO`, `TAGLIANDO`, `REVISIONE`; persistito per nome; `fromCodice` ripiega su `ALTRO` |
| `SyncMerger.Esito` | Modifica | Nuovo valore `SCARTATO_INVALIDO` |

### Interfacce di sincronizzazione e API interne

L'app non ha endpoint HTTP: i contratti esterni sono i messaggi MQTT e Bluetooth.

| Canale / Classe | Interfaccia | Descrizione |
|---|---|---|
| MQTT | `sync/{groupId}/manutenzioni/{id}` | JSON Gson dell'entity, QoS 1, retained; payload vuoto = eliminazione. Auth: credenziali del broker esistenti |
| Bluetooth | `DataPayload.manutenzioni` | Lista delle manutenzioni delle auto concordate |
| Bluetooth | `VeicoloOfferto.nManutenzioni` | Conteggio mostrato nell'offerta |
| `MqttSyncManager` | `publishManutenzione(Manutenzione)`, `publishDeleteManutenzione(String)` | Chiamati dalla schermata dopo la scrittura su DB |
| `SyncMerger` | `applyManutenzione(Manutenzione)`, `deleteManutenzione(String)` | Merge last-write-wins, controllo FK e campi obbligatori |
| `SyncMerger` | `adottaIdVeicolo(...)` → `Spostamento` | Restituisce i rifornimenti e le manutenzioni spostati (prima un `int`) |
| `ManutenzioneActivity` | `EXTRA_VEICOLO_ID` | Auto da preselezionare |

Dettaglio di DAO, SQL e JSON: [Fase 2, sezione B](phase-2-analysis.md#b-contratti-e-interfacce).

### Breaking changes

| Componente | Tipo di breaking change | Piano di migrazione |
|---|---|---|
| Protocollo Bluetooth | `PROTOCOL_VERSION` 1 → 2: le versioni 1 e 2 non si sincronizzano più fra loro (D8) | Aggiornare entrambi i telefoni; il rifiuto è già gestito con il messaggio `bt_err_versione` |
| Schema DB | Additivo, ma **non reversibile**: un'app con DB 5 non apre un DB 6 (manca la migrazione di downgrade e non c'è `fallbackToDestructiveMigrationOnDowngrade`) | Rollback solo correggendo in avanti (vedi §9) |
| MQTT | Nessuno: le versioni precedenti non sottoscrivono il nuovo topic | — |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da | Responsabile |
|---|---|---|---|---|---|
| T-01 | `Manutenzione`, `TipoManutenzione`, `ManutenzioneDao` | BE | 0,25 | — | A. Goldoni / Claude |
| T-02 | `AppDatabase` v6: build, copia dello SQL da `AppDatabase_Impl`, `MIGRATION_5_6` | BE | 0,25 | T-01 | A. Goldoni / Claude |
| T-03 | Prova di migrazione (TC-01) sulla debug del Redmi Note 13 Pro | Test | 0,10 | T-02 | A. Goldoni / Claude |
| T-04 | `SyncMerger`: `applyManutenzione`, `deleteManutenzione`, `SCARTATO_INVALIDO`, `adottaIdVeicolo` con `Spostamento` | BE | 0,25 | T-01 | A. Goldoni / Claude |
| T-05 | Stringhe, icona `ic_manutenzione`, voce `nav_manutenzioni`, registrazione nel manifest; `versionName` 1.1 e `versionCode` 2 in `app/build.gradle` | FE | 0,10 | — | A. Goldoni / Claude |
| T-06 | `ManutenzioneActivity`, `ManutenzioneAdapter`, i tre layout, dialog con validazione | FE | 0,75 | T-01, T-05 | A. Goldoni / Claude |
| T-07 | `MainActivity`: apertura con `EXTRA_VEICOLO_ID`; listener dati MQTT in `onResume` | FE | 0,15 | T-06 | A. Goldoni / Claude |
| T-08 | `MqttSyncManager`: sottoscrizione, `handleManutenzione`, publish, sync completa | BE | 0,35 | T-04 | A. Goldoni / Claude |
| T-09 | Bluetooth v2: `BtMessages`, `BluetoothSyncManager`, `BluetoothSyncActivity`, `BtOfferAdapter` | BE | 0,40 | T-04 | A. Goldoni / Claude |
| T-10 | Debug v1 compilata da `905e6c2` in un worktree detached, per TC-12 | Test | 0,05 | — | A. Goldoni / Claude |
| T-11 | Prove TC-02…TC-13 sui due telefoni | Test | 0,60 | T-03, T-07, T-08, T-09, T-10 | A. Goldoni / Claude |
| T-12 | Sezione "Esito dell'implementazione" in questo documento, aggiornamento dei documenti di fase, commit unico | Doc | 0,25 | T-11 | A. Goldoni / Claude |

**Stima totale:** 3,5 giorni/uomo
**Breakdown:** BE 1,5 gg · FE 1,0 gg · Test 0,75 gg · Doc 0,25 gg

Ordine consigliato: T-01 → T-02 → T-03 (fermarsi se la migrazione fallisce) → T-04 → T-05 →
T-06 → T-07 → T-08 → T-09 → T-10 → T-11 → T-12. Ogni task termina con `./build.sh debug` senza
errori.

**Commit:** uno solo, su `main`, a fine feature (dopo T-12). Comprende codice, documenti di fase e
la modifica alle convenzioni del CLAUDE.md.

---

## 7. Piano di test

**Strategia generale:** prove manuali end-to-end sui due telefoni fisici, **solo con build debug**
(`adb install -r`, che conserva i dati). Le release installate non vengono toccate. Nessun test
automatico in questa feature (confermato il 2026-10-05): il progetto non ha infrastruttura di test
e i punti a rischio (migrazione Room, sync) richiederebbero test strumentali con schema esportato.

Dispositivi:
- **A** — Redmi Note 13 Pro, Android 16, `m7mvnndmhqugnbr8`: debug con dati reali (DB v5).
- **B** — REDMI Note 15, Android 15, `QGNVUCRS85HAUKBI`: debug da installare.

**Sequenza di installazione obbligata.** La nuova debug ha `versionCode` 2 e DB 6, quella di
`905e6c2` ha `versionCode` 1 e DB 5: dopo la nuova non si può più installare la vecchia (servirebbe
un downgrade e l'app andrebbe in crash sul DB 6). Quindi, installando per singolo dispositivo
(`adb -s <serial> install -r`) e non con `install-all.sh`:
1. A: nuova debug sopra la debug attuale → TC-01…TC-06.
2. B: debug di `905e6c2` (DB 5 vuoto) → TC-12 con A.
3. B: nuova debug sopra la v1 → verifica anche la migrazione su DB vuoto → TC-07…TC-11, TC-13.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | E2E (A) | Nuova debug sopra il DB v5: nessun crash, 3 auto e 11 rifornimenti invariati, `PRAGMA user_version` = 6 | Alta |
| TC-02 | E2E (A) | Inserimento di un intervento per ciascun tipo, con e senza km; ordinamento | Alta |
| TC-03 | E2E (A) | Validazione: tipo mancante, "Altro" senza descrizione, costo vuoto/negativo/0, km negativi o non interi | Alta |
| TC-04 | E2E (A) | Modifica ed eliminazione con conferma | Alta |
| TC-05 | E2E (A) | Cambio auto; stato senza auto | Media |
| TC-06 | E2E (A) | Eliminazione di un'auto con interventi (cascade) | Alta |
| TC-07 | E2E (A↔B) | MQTT: inserimento, modifica, eliminazione propagati; scarto per auto assente nel log | Alta |
| TC-08 | E2E (A↔B) | MQTT: "Riconnetti ora" ripubblica anche le manutenzioni | Media |
| TC-09 | E2E (A↔B) | MQTT con schermata manutenzioni aperta; poi la principale continua ad aggiornarsi | Media |
| TC-10 | E2E (A↔B) | Bluetooth v2: insieme allineato, offerta e riepilogo con le manutenzioni | Alta |
| TC-11 | E2E (A↔B) | Bluetooth con collegamento auto locale ↔ remota: interventi conservati | Alta |
| TC-12 | E2E (A↔B) | Bluetooth v1 (debug da `905e6c2`) contro v2: sessione rifiutata su entrambi | Media |
| TC-13 | Regressione | Rifornimenti, consumi, CSV, sync MQTT e Bluetooth dei rifornimenti | Alta |

### Definition of Done per QA

- [ ] `./build.sh debug` senza errori né nuovi warning di compilazione
- [ ] TC-01…TC-13 superati; quelli non eseguibili sono dichiarati come tali, con il motivo
- [ ] Nessuna voce `ERROR` nella diagnostica sync durante le prove, salvo quelle provocate apposta
- [ ] Entrambi i telefoni lasciati con la nuova debug installata
- [ ] Documento di implementazione scritto e documenti di fase aggiornati
- [ ] Revisione del diff (es. `/code-review`) senza problemi aperti

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Crash all'avvio per SQL di migrazione diverso dallo schema atteso da Room | Media | Alto | SQL copiato da `AppDatabase_Impl`; TC-01 subito dopo T-02. In caso di errore la migrazione gira in transazione e il DB resta alla v5 con i dati intatti: si corregge e si reinstalla |
| Perdita degli interventi collegando un'auto via Bluetooth (cascade in `adottaIdVeicolo`) | Alta se non gestito | Alto | `reassignVeicolo` delle manutenzioni nella stessa transazione, prima della `delete`; TC-11 |
| La schermata principale smette di aggiornarsi sui dati MQTT (listener unico) | Alta se non gestito | Medio | Registrazione del listener in `onResume` in entrambe le activity; TC-09 |
| Record Bluetooth malformato che annulla l'intera sessione | Bassa | Alto | Controllo dei campi obbligatori in `SyncMerger` (`SCARTATO_INVALIDO`) prima della scrittura |
| Crash nel riepilogo Bluetooth per argomenti di formato non allineati | Bassa | Medio | `bt_riepilogo_corpo` e `onConclusa` modificati insieme; TC-10 |
| Tipo sconosciuto da una versione futura | Bassa | Basso | `fromCodice` ripiega su "Altro" in visualizzazione; il codice salvato non viene riscritto |
| Telefoni con versioni diverse non si sincronizzano via Bluetooth | Certa (voluta) | Basso | Aggiornare entrambi; messaggio d'errore già presente |
| Avvisi ripetuti nel log per i retained orfani dopo l'eliminazione di un'auto | Media | Basso | Accettato: comportamento preesistente, fuori scope |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] Deploy diretto (direct), **solo build debug** sui due telefoni di test con `install-all.sh`
- [ ] Graduale con feature flag
- [ ] Canary release

**Feature flag:** nessuno. L'app non ha un meccanismo di flag; la feature è additiva e non cambia
il comportamento esistente se non la si usa (a parte il protocollo Bluetooth v2).

**Piano di rollback:**
1. Il downgrade dell'app non è possibile senza perdere i dati: una build con DB 5 va in crash su un
   DB 6. Il rollback si fa **correggendo in avanti** e reinstallando la debug con `install-all.sh`.
2. Se fallisce la migrazione (TC-01), il DB resta alla v5 perché Room la esegue in transazione:
   si corregge `MIGRATION_5_6` e si reinstalla, senza perdita di dati.
3. Il ripristino del codice si fa con `git revert` del commit della feature, senza cambiare
   branch; la build risultante, però, non si installa sopra un DB già migrato (vedi punto 1).

> ⚠️ DA COMPLETARE: quando pubblicare la release 1.1 e aggiornare le release installate sui
> telefoni. Lo decide Alberto Goldoni dopo l'esito delle prove; non fa parte di questo piano.

---

## 10. Checklist di approvazione

Progetto personale: un solo approvatore per tutte le revisioni.

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto Goldoni | ✅ Approvata | 2026-10-05 |
| Revisione prodotto (requisiti, Fase 1) | Alberto Goldoni | ✅ Approvata | 2026-10-05 |
| Analisi tecnica (Fase 2) | Alberto Goldoni | ✅ Approvata | 2026-10-05 |
| Stima approvata | Alberto Goldoni | ✅ Approvata | 2026-10-05 |
| Rischi accettati | Alberto Goldoni | ✅ Approvata | 2026-10-05 |
| Data di inizio confermata | Alberto Goldoni | ✅ Avviata | 2026-10-05 |

---

## Esito dell'implementazione

> Completato il 2026-10-05. Tutti i task T-01…T-12 eseguiti; TC-01…TC-13 superati sui due
> telefoni, solo con build debug.

### Cosa è stato fatto

| Area | File |
|---|---|
| Dati | Nuovi `Manutenzione` (con gli helper per le date ISO), `TipoManutenzione`, `ManutenzioneDao`; `AppDatabase` v6 con `MIGRATION_5_6` (SQL copiato da `AppDatabase_Impl`) |
| Merge | `SyncMerger`: `applyManutenzione`, `deleteManutenzione`, `Esito.SCARTATO_INVALIDO`, `adottaIdVeicolo` → `Spostamento` con le manutenzioni ripuntate nella stessa transazione |
| UI | Nuovi `ManutenzioneActivity`, `ManutenzioneAdapter`, `activity_manutenzione.xml`, `item_manutenzione.xml`, `dialog_add_manutenzione.xml`, `ic_manutenzione.xml`; voce `nav_manutenzioni`; registrazione nel manifest; `MainActivity` apre la schermata con `EXTRA_VEICOLO_ID` |
| MQTT | `MqttSyncManager`: topic `manutenzioni/#`, `handleManutenzione`, `publishManutenzione`, `publishDeleteManutenzione`, manutenzioni nella sync completa |
| Bluetooth | `BtMessages` v2 (`DataPayload.manutenzioni`, `VeicoloOfferto.nManutenzioni`); `BluetoothSyncManager`, `BluetoothSyncActivity`, `BtOfferAdapter` con offerta, scambio e riepilogo |
| Build | `versionName` 1.1, `versionCode` 2 |
| Documenti | Questa sezione; convenzioni dei documenti di feature nel CLAUDE.md |

### Scostamenti dal piano

1. **Listener dati MQTT rilasciato anche in `onPause`** (non solo ripreso in `onResume`), in
   `MainActivity` e `ManutenzioneActivity`. Senza, un'activity distrutta in background resterebbe
   registrata e un messaggio in arrivo userebbe il suo executor già chiuso. Di conseguenza
   `MqttSyncManager.notifyListener` legge il listener in una variabile locale (campo ora
   `volatile`): prima lo rileggeva dentro la lambda e un `null` nel frattempo avrebbe causato un
   crash.
2. **Virgola nel campo costo** (emerso in TC-03): con il solo `inputType="numberDecimal"` la
   virgola della tastiera veniva scartata anche con il telefono in `it-IT`, quindi il criterio
   "accetta `,` e `.`" non era soddisfatto. Corretto nel dialog delle manutenzioni con
   `android:digits="0123456789.,"`, che mantiene la tastiera numerica. **Il dialog dei
   rifornimenti ha lo stesso difetto ed è stato lasciato com'era** (fuori scope).
3. **Errore di versione Bluetooth** (emerso in TC-12): il controllo di versione inviava il rifiuto
   prima di mostrare l'errore; l'altro lato, che fa lo stesso controllo, aveva già chiuso il
   socket e chi inviava per secondo vedeva "Broken pipe" invece del messaggio sulle versioni.
   Difetto preesistente, diventato raggiungibile con il passaggio al protocollo v2: ora l'errore
   di versione si mostra comunque e la mancata consegna del rifiuto si ignora. Una v1 che fa da
   **mittente** verso una v2 può ancora mostrare "Broken pipe" dal proprio lato: il codice v1 non
   si può correggere.
4. `bt_riepilogo_corpo` ha 8 argomenti invece dei 7 previsti: include anche le manutenzioni
   inviate, per simmetria con i rifornimenti.

### Esito delle prove

Telefono **A** = Redmi Note 13 Pro (debug con dati reali), **B** = REDMI Note 15.

| ID | Esito | Note |
|---|---|---|
| TC-01 | ✅ | A: DB 5 → 6, 3 auto e 11 rifornimenti invariati. B: migrazione anche da v1 su DB vuoto |
| TC-02 | ✅ | Tre tipi, con e senza km; ordine per data e, a parità, per ultima modifica |
| TC-03 | ✅ | Dopo la correzione della virgola (scostamento 2) |
| TC-04 | ✅ | Dialog precompilato, menu del tipo non filtrato, km svuotati → `NULL`; eliminazione con conferma |
| TC-05 | ✅ | Cambio auto su A; stato senza auto su B (FAB disabilitato, messaggio) |
| TC-06 | ✅ | Auto di prova eliminata con le sue manutenzioni; testo di conferma aggiornato |
| TC-07 | ✅ | Inserimento ~4 s, modifica ~3 s, eliminazione ~3 s (retained rimosso); auto inesistente → WARN, data mancante → ERROR, app attiva |
| TC-08 | ✅ | "Sync completa avviata: 3 veicoli, 11 rifornimenti e 4 manutenzioni pubblicati" |
| TC-09 | ✅ | Elenco aperto aggiornato; principale aggiornata dopo il ritorno |
| TC-10 | ✅ | Offerta "… • 1 manutenzione"; stesso insieme su A e B, campi identici (km `NULL` compreso) |
| TC-11 | ✅ | Su B l'auto locale ha adottato l'id di A: "0 rifornimenti e 1 manutenzioni mantenuti" |
| TC-12 | ✅ | v2 mittente contro v1 ricevente: messaggio sulle versioni su entrambi, dopo la correzione (scostamento 3) |
| TC-13 | ✅ | Consumi, export CSV, rifornimenti sincronizzati via MQTT e Bluetooth. L'inserimento manuale di un rifornimento non è stato riprovato: richiede il permesso di posizione, codice invariato |

Lint: nessun errore; tre warning nei file nuovi, gli stessi già presenti nei file dei rifornimenti
(`NotifyDataSetChanged`, campo data cliccabile, etichetta del menu a tendina).

### Ambiente di prova e stato finale

- **Broker MQTT**: container Mosquitto temporaneo su `127.0.0.1:18830`, raggiunto dai telefoni con
  `adb reverse`. Nessun dato è uscito dalla macchina. Container, `adb reverse` e worktree della v1
  sono stati rimossi.
- **Telefono A**: debug 1.1 con i dati originali (3 auto, 11 rifornimenti, nessuna manutenzione);
  sync MQTT disabilitata nell'app debug (configurazione verso il broker di prova).
- **Telefono B**: debug 1.1 vuota, sync MQTT disabilitata.
- **Bluetooth**: i due telefoni restano associati. Senza associazione A non trovava B nella
  ricerca, anche con B visibile (vedi sotto).
- **Release** installate su A e B: non toccate.

### Seguiti suggeriti (fuori da questa feature)

1. Accettare la virgola anche nel dialog dei rifornimenti (stessa correzione dello scostamento 2).
2. Ricerca Bluetooth: il Redmi Note 13 Pro non trovava il REDMI Note 15 (HyperOS 2) pur visibile;
   funziona solo fra telefoni già associati. Da indagare.
3. Pulizia dei retained orfani sul broker quando si elimina un'auto (rischio E8 della Fase 2).
4. Le sezioni "Project Overview" e "Architecture" del CLAUDE.md descrivono ancora uno starter
   template.

---

## Domande aperte

Nessuna. Risposte di Alberto Goldoni del 2026-10-05:

| # | Domanda | Risposta |
|---|---|---|
| 1 | Commit per milestone o a fine feature | **Un solo commit, a fine feature** |
| 2 | Test automatici (JUnit) | **Nessuno** in questa feature |
| 3 | Convenzione dei documenti di feature | **Adottare quella della skill**: niente `prompt.md` / `plan.md` / `implementation.md`; l'esito va in questo documento; CLAUDE.md aggiornato di conseguenza |
| 4 | Versione dell'app | **`versionName` 1.1, `versionCode` 2** |

---

*Documento generato con la skill `claude-code-feature`.*

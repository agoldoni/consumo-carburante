# Manutenzioni — Fase 1: requisiti

> Feature: registrazione degli interventi di manutenzione di un'auto.
> Stato: **approvato** (2026-10-05) · Data: 2026-10-05

## Richiesta originale

> prevedere anche la registrazione di interventi di manutenzione.
> - data (solo giorno)
> - tipo: altro, tagliando, revisione
> - descrizione
> - costo
>
> Integrazione: aggiungere anche i km percorsi all'atto della manutenzione, opzionale.

## Contesto

Oggi l'app registra solo i **rifornimenti**, legati a un'auto (`Veicolo`) e mostrati nella
schermata principale filtrati per l'auto selezionata nello spinner. I dati si sincronizzano fra
installazioni su due canali:

- **MQTT** (`MqttSyncManager`): tutte le auto e tutti i rifornimenti del gruppo, un record per
  topic `sync/{groupId}/{tipo}/{id}`; le eliminazioni viaggiano come messaggio con payload vuoto.
- **Bluetooth** (`BluetoothSyncManager`, `BtMessages`): scambio one-shot e selettivo per auto.

Entrambi i canali fondono i record con la stessa regola last-write-wins su `updatedAt`, centralizzata
in `SyncMerger`.

Le manutenzioni sono un secondo tipo di record per auto, accanto ai rifornimenti: devono seguire
lo stesso modello (UUID, `updatedAt`, foreign key verso `veicoli`) per poter viaggiare sugli stessi
canali senza regole nuove.

---

## 1. Obiettivo e motivazione

Avere nell'app, accanto ai rifornimenti, lo storico degli interventi fatti sull'auto (tagliandi,
revisioni, riparazioni varie) con la data e quanto sono costati. Risponde a domande pratiche come
"quando ho fatto l'ultimo tagliando?", "quando scade la revisione?" (data dell'ultima + 2 anni) e
"quanto ho speso di manutenzione su quest'auto?", senza dover tenere un quaderno o un foglio a parte.

Chi condivide un'auto con un'altra persona deve vedere lo stesso storico sui due telefoni, come già
avviene per i rifornimenti.

## 2. Scope

### Incluso

- **Modello dati**: nuova tabella `manutenzioni` con
  - `data` — solo giorno, senza ora;
  - `tipo` — uno fra **Altro**, **Tagliando**, **Revisione**;
  - `descrizione` — testo libero;
  - `costo` — euro, come per i rifornimenti;
  - `km` — lettura del contachilometri al momento dell'intervento, intero, **facoltativa**;
  - riferimento all'auto, `id` UUID e `updatedAt` per la sincronizzazione.
- **Migrazione** del database dalla versione 5 alla 6, senza perdita dei dati esistenti.
- **Schermata Manutenzioni**, raggiungibile dal menu laterale, che lavora sull'auto selezionata:
  - elenco degli interventi dal più recente, con data, tipo, descrizione, km e costo;
  - aggiunta tramite dialog (data con DatePicker, tipo, descrizione, km, costo);
  - modifica ed eliminazione (con conferma), come per i rifornimenti.
- **Sincronizzazione MQTT** delle manutenzioni: pubblicazione su inserimento, modifica ed
  eliminazione, ricezione, inclusione nella "sync completa" (`republishAll`).
- **Sincronizzazione Bluetooth** delle manutenzioni delle auto concordate, nello stesso scambio
  dei rifornimenti.
- **Coerenza con le operazioni sull'auto**: eliminare un'auto elimina le sue manutenzioni; il
  collegamento di un'auto locale a una remota (adozione dell'id) le sposta insieme ai rifornimenti.
- Voci nella diagnostica di sync (`SyncLog`) per le manutenzioni ricevute, aggiornate, scartate.

### Escluso (out of scope)

- **Promemoria e scadenze** (notifica del prossimo tagliando o della revisione in scadenza).
- **Tipi personalizzabili** dall'utente: i tre tipi sono fissi.
- **Esportazione e importazione CSV** delle manutenzioni (vedi "Decisioni da confermare").
- **Allegati** (foto della fattura, PDF).
- **Statistiche combinate** carburante + manutenzione (costo totale di esercizio, costo/km
  complessivo).
- Visualizzazione delle manutenzioni mescolate ai rifornimenti nella schermata principale.

## 3. User Stories

1. **Registrazione** — Come proprietario dell'auto voglio registrare un intervento con data, tipo,
   descrizione e costo, per tenere traccia di cosa è stato fatto e quanto è costato.
2. **Consultazione** — Come proprietario dell'auto voglio vedere l'elenco degli interventi
   dell'auto selezionata, dal più recente, per sapere a colpo d'occhio quando ho fatto l'ultimo
   tagliando o l'ultima revisione.
3. **Correzione** — Come utente voglio modificare o eliminare un intervento già registrato, per
   correggere un errore di inserimento.
4. **Condivisione** — Come persona che condivide un'auto con un familiare voglio che gli interventi
   registrati da uno dei due compaiano anche sul telefono dell'altro (via MQTT o Bluetooth), per
   avere un unico storico dell'auto.
5. **Aggiornamento sicuro** — Come utente che aggiorna l'app voglio ritrovare intatti auto e
   rifornimenti già registrati, per non perdere lo storico.

## 4. Criteri di accettazione

**US1 — Registrazione**
- [ ] Dal menu laterale si apre la schermata "Manutenzioni" dell'auto selezionata.
- [ ] Il pulsante "+" apre un dialog con: data (precompilata a oggi, modificabile con DatePicker,
      senza ora), tipo (Altro / Tagliando / Revisione), descrizione, km, costo.
- [ ] Il campo km è facoltativo: se vuoto l'intervento si salva senza km; se compilato accetta
      solo un intero non negativo.
- [ ] Il costo accetta sia `,` sia `.` come separatore decimale; un valore non numerico o negativo
      viene segnalato sul campo e blocca il salvataggio.
- [ ] Data e tipo sono sempre valorizzati; il salvataggio senza tipo non è possibile.
- [ ] Dopo il salvataggio l'intervento compare in elenco senza riavviare la schermata.
- [ ] Se non esiste alcuna auto la schermata lo dice e non consente l'inserimento, come la
      schermata principale.

**US2 — Consultazione**
- [ ] L'elenco mostra solo gli interventi dell'auto selezionata, ordinati per data decrescente.
- [ ] Ogni voce mostra data (`dd/MM/yyyy`), tipo, descrizione e costo in euro con formato italiano;
      i km compaiono (con separatore delle migliaia) solo se presenti.
- [ ] Con elenco vuoto compare un messaggio esplicativo.
- [ ] Cambiando auto nella schermata l'elenco si aggiorna.

**US3 — Correzione**
- [ ] Ogni voce offre modifica ed eliminazione con le stesse modalità dei rifornimenti.
- [ ] La modifica apre il dialog precompilato e aggiorna `updatedAt`.
- [ ] L'eliminazione chiede conferma.

**US4 — Condivisione**
- [ ] MQTT: un intervento inserito, modificato o eliminato su un telefono compare, cambia o sparisce
      sull'altro telefono dello stesso gruppo entro pochi secondi, con entrambi connessi.
- [ ] MQTT: la "sync completa" ripubblica anche le manutenzioni.
- [ ] Bluetooth: al termine di una sessione i due telefoni hanno lo stesso insieme di manutenzioni
      per le auto concordate; il riepilogo finale riporta anche le manutenzioni nuove/aggiornate.
- [ ] Un intervento modificato su entrambi i telefoni si risolve a favore della modifica più recente
      (stessa regola dei rifornimenti).
- [ ] Un intervento ricevuto per un'auto che non esiste in locale viene scartato e annotato nel log
      di sync, senza errori.
- [ ] Collegando un'auto locale a una remota via Bluetooth, le manutenzioni dell'auto locale non
      vanno perse.
- [ ] Un telefono con la versione precedente dell'app nello stesso gruppo MQTT continua a funzionare
      (ignora semplicemente le manutenzioni).

**US5 — Aggiornamento sicuro**
- [ ] Installando la nuova versione sopra la precedente, auto e rifornimenti restano invariati e la
      tabella delle manutenzioni è vuota.
- [ ] L'app non va in crash all'avvio dopo l'aggiornamento (validazione schema Room superata).

## 5. Rischi e dipendenze

**Tecnici**
- **Migrazione Room**: `exportSchema = false` e nessun test di migrazione; Room valida lo schema
  all'apertura e va in crash se lo SQL scritto a mano non coincide con quello atteso dall'entity
  (tipi, nullabilità, indici, foreign key). Va provata installando sopra una build con dati reali.
- **Data "solo giorno"**: salvarla come epoch millis a mezzanotte la renderebbe dipendente dal fuso
  orario del telefono che l'ha scritta (un giorno può slittare fra due dispositivi). Con
  `minSdk 24` `java.time.LocalDate` non è disponibile senza desugaring: serve una rappresentazione
  indipendente dal fuso (es. stringa `yyyy-MM-dd`). Da decidere in Fase 2.
- **Collegamento auto via Bluetooth** (`SyncMerger.adottaIdVeicolo`): oggi ripunta i rifornimenti
  e poi elimina la vecchia auto; con la `ON DELETE CASCADE` le manutenzioni non ripuntate verrebbero
  **cancellate**. Va esteso nella stessa transazione.
- **Compatibilità del protocollo Bluetooth**: un telefono con la versione precedente ignorerebbe in
  silenzio il nuovo campo del payload. Da valutare se incrementare `PROTOCOL_VERSION` (blocco
  esplicito fra versioni diverse) o accettare la degradazione silenziosa.
- **Km facoltativi**: il campo è nullable (`Integer`, colonna `INTEGER` senza `NOT NULL`),
  diversamente da `Rifornimento.km` che è un `int` obbligatorio. Va mantenuta la differenza fra
  "km assenti" e "0 km" in UI, nel merge e nel JSON dei canali di sync (Gson omette i `null`).
- **Compatibilità MQTT**: le vecchie installazioni sottoscrivono solo `veicoli/#` e
  `rifornimenti/#`, quindi un nuovo topic `manutenzioni/#` non le raggiunge: rischio basso.
- **Ordine di arrivo MQTT**: una manutenzione può arrivare prima della sua auto (scartata per
  foreign key); è lo stesso comportamento già accettato per i rifornimenti, recuperabile con la
  sync completa.

**Di progetto**
- Nessun test automatico nel progetto: la verifica è manuale, sullo smartphone collegato via USB
  (oggi risulta `unauthorized` in adb: va autorizzato il debug USB sul telefono) e, per la sync,
  su un secondo dispositivo o emulatore.
- Per provare MQTT serve il broker configurato e raggiungibile da entrambi i dispositivi.

## 6. Stima effort

Stima per un solo sviluppatore che conosce la codebase.

| Area | Attività | gg/uomo |
|---|---|---|
| BE (dati e sync) | Entity, DAO, migrazione 5→6, `SyncMerger`, MQTT, Bluetooth | 1,5 |
| FE (UI) | Activity elenco, adapter, layout voce, dialog aggiunta/modifica, voce di menu, stringhe | 1,0 |
| Test | Migrazione su dati reali, CRUD su dispositivo, sync MQTT e Bluetooth fra due dispositivi | 0,75 |
| Documentazione | Documenti di fase, nota di implementazione | 0,25 |
| **Totale** | | **3,5** |

## 7. Milestones

1. **Modello dati** — entity `Manutenzione`, `ManutenzioneDao`, registrazione in `AppDatabase`,
   migrazione 5→6. *Verifica*: build e avvio su una installazione esistente senza crash.
2. **Merge** — `SyncMerger` esteso con applicazione ed eliminazione delle manutenzioni;
   `adottaIdVeicolo` sposta anche le manutenzioni.
3. **UI** — schermata Manutenzioni con elenco, dialog di aggiunta/modifica, eliminazione, voce nel
   menu laterale. *Verifica*: CRUD completo sullo smartphone.
4. **Sync MQTT** — publish su CRUD, sottoscrizione e gestione del nuovo topic, inclusione nella sync
   completa, voci nel log. *Verifica*: due dispositivi nello stesso gruppo.
5. **Sync Bluetooth** — manutenzioni nel payload dati, conteggi nell'offerta e nel riepilogo,
   decisione sulla versione di protocollo. *Verifica*: sessione fra due dispositivi.
6. **Chiusura** — prova di regressione su rifornimenti e sync esistenti, documento di
   implementazione.

---

## Decisioni (approvate)

Tutti i default sono stati approvati il 2026-10-05.

| # | Decisione | Esito |
|---|---|---|
| D1 | Dove si accede alle manutenzioni | Nuova voce "Manutenzioni" nel menu laterale, schermata separata con il proprio selettore dell'auto (preimpostato sull'auto scelta nella schermata principale) |
| D2 | Descrizione obbligatoria? | Facoltativa, ma **obbligatoria se il tipo è "Altro"** (altrimenti la voce non dice cosa è stato fatto) |
| D3 | Costo obbligatorio? | Obbligatorio, ammesso `0` (interventi in garanzia) |
| D4 | Tipo preselezionato nel dialog | Nessuno: l'utente deve sceglierlo esplicitamente |
| D5 | Campo km dell'intervento | ✅ **Deciso**: incluso, facoltativo |
| D6 | Totale speso in testa all'elenco | Escluso |
| D7 | Esportazione CSV delle manutenzioni | Esclusa da questa feature |
| D8 | Sync con versioni diverse dell'app via Bluetooth | Incrementare `PROTOCOL_VERSION`: due versioni diverse rifiutano la sessione invece di perdere dati in silenzio |

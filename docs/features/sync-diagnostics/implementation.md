# Implementazione — Diagnostica sync

## File aggiunti

| File | Contenuto |
|---|---|
| `SyncLog.java` | diario eventi: buffer in memoria (500) + file `sync_log.txt`, contatori su `SharedPreferences`, listener con throttle |
| `SyncLogAdapter.java` | adapter della lista eventi (ordine dal più recente) |
| `SyncStatusActivity.java` | la vista di diagnostica |
| `res/layout/activity_sync_status.xml` | stato, contatori, pulsante riconnetti, lista |
| `res/layout/item_sync_log.xml` | riga evento: barra livello, ora, categoria, messaggio |
| `res/menu/menu_sync_status.xml` | condividi log / svuota log |
| `docs/features/sync-diagnostics/*` | questa documentazione |

## File modificati

- **`MqttSyncManager.java`** — strumentato in tutti i punti del ciclo di vita
  (connessione, sottoscrizioni, pubblicazioni, ricezione). Aggiunti
  `forceReconnect()` e `getClientStateLabelRes()`. I quattro metodi `publish*`
  ora delegano a un unico `publish(...)` privato e le due sottoscrizioni a
  `subscribe(...)`: stessa logica di prima, meno duplicazione da mantenere.
- **`MainActivity.java`** — voce di drawer `nav_sync_status`; l'icona di stato
  nella toolbar apre la vista invece di mostrare un toast.
- **`AndroidManifest.xml`**, **`nav_menu.xml`**, **`strings.xml`**,
  **`colors.xml`**, **`themes.xml`** — registrazione activity, voce di menu,
  stringhe `sync_*`, colori dei livelli, stili dei contatori.

## Decisioni

- **Persistenza su file di testo** invece che su Room: il diario è dati
  diagnostici volatili, non fa parte del modello dell'app e non deve entrare
  nelle migrazioni del database. La rotazione a 128 KB tiene il file sotto
  controllo; gli errori di I/O sono ignorati per non far mai fallire la sync.
- **Nessun secondo listener di connessione**: la vista si aggiorna sugli eventi
  di `SyncLog` (ogni cambio di stato ne produce uno) e legge lo stato corrente al
  refresh. Così il listener singolo di `MqttSyncManager`, già usato da
  `MainActivity` per l'icona in toolbar, non viene sovrascritto.
- **Aggregazione dei messaggi già allineati**: ad ogni riconnessione il broker
  rimanda tutti i retained; loggarli singolarmente svuoterebbe il buffer degli
  eventi utili. Vengono contati e riassunti in un solo evento dopo 1,5 s di
  quiete.
- **Stato del client HiveMQ esposto** (`CONNECTING_RECONNECT`,
  `DISCONNECTED_RECONNECT`, …): distingue "disconnesso e fermo" da "sta
  riprovando", informazione che il solo flag `connected` non dava.
- **Nessuna credenziale** nella vista né nel log condiviso: broker, porta, TLS,
  gruppo e client id sì, username e password mai.
- **Messaggi d'errore ripuliti**: `SyncLog.describe()` toglie il nome di classe
  qualificato che le librerie antepongono al messaggio
  (`io.netty…AnnotatedConnectException: Connection refused` → `Connection refused`).
- **Tentativi ripetuti non ripetuti nel log**: con broker irraggiungibile il
  reconnect riprova all'infinito; se la causa non cambia vengono registrati i
  primi tre tentativi e poi uno ogni cinque.

## Verifica su dispositivo

Provata sulla build debug su Redmi 23117RA68G (HyperOS, Android 16):

- **broker irraggiungibile** (127.0.0.1:1883): stato «In attesa di riconnessione»,
  eventi `CONN` con causa e backoff crescente (2s, 4s, 9s, 29s), tentativi
  ripetuti diradati come previsto;
- **broker raggiungibile** (mosquitto in container, esposto al telefono con
  `adb reverse tcp:1883 tcp:1883`): `CONN` connesso → due `SUB` → `PUB` sync
  completa (3 veicoli, 11 rifornimenti) → i 14 retained di ritorno aggregati in
  un solo evento «14 messaggi ricevuti erano già allineati»;
- **ricezione**: pubblicando un veicolo di prova e poi il suo tombstone sono
  comparsi «Nuovo veicolo ricevuto» e «Veicolo eliminato da remoto», con i
  contatori aggiornati (14 inviati / 2 ricevuti / 0 falliti / 0 cadute).

Al termine la configurazione di prova, il diario e i contatori sono stati
rimossi dalla build debug.

## Come si usa

Menu laterale → *Diagnostica sync*, oppure tap sull'icona di sync nella toolbar.
Da lì: *Riconnetti ora*, condivisione del log come file di testo, svuotamento del
diario.

## Cosa cercare nel log quando la sync non funziona

Il diario è stato pensato per far emergere i sospetti principali:

1. **`CONN` — "Tentativo di connessione fallito"** ripetuto: broker irraggiungibile,
   credenziali errate o TLS/porta non coerenti. La causa riportata è quella del
   client MQTT.
2. **`CONN` — "Client già attivo ma non connesso"**: l'app sta aspettando la
   riconnessione automatica di HiveMQ; se lo stato resta così a lungo, il pulsante
   *Riconnetti ora* forza un client nuovo.
3. **`PUB` — "Offline: … non inviato"**: il dato è stato salvato solo in locale.
   Verrà ripubblicato alla riconnessione dalla sync completa — **tranne le
   eliminazioni**, che non vengono ripetute: un rifornimento eliminato mentre si è
   offline resta cancellato in locale ma può essere ripubblicato dall'altro
   dispositivo.
4. **`RECV` — "Rifornimento … scartato: veicolo … non presente in locale"**: i
   messaggi *retained* di veicoli e rifornimenti arrivano su due sottoscrizioni
   distinte e senza un ordine garantito; se i rifornimenti arrivano per primi
   vengono scartati e non vengono più riproposti finché il mittente non li
   ripubblica.

I punti 3 e 4 sono limiti attuali del protocollo di sync, non della diagnostica:
la vista li rende visibili, la loro risoluzione è un intervento successivo.

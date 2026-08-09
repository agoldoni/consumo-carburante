# Piano di implementazione — Diagnostica sync

## 1. Raccolta eventi (`SyncLog`)

Singleton, sullo stile di `MqttSyncManager`.

- Buffer in memoria degli ultimi 500 eventi (`ArrayDeque`) + file
  `filesDir/sync_log.txt` per la persistenza, con rotazione a 128 KB.
- Ogni evento: timestamp, livello (INFO/WARN/ERROR), categoria
  (CONN/SUB/PUB/RECV/DB), messaggio.
- Scritture serializzate su un `ExecutorService` a thread singolo: il primo task
  in coda è il caricamento dello storico, così gli append successivi non lo
  scavalcano.
- Contatori e ultimo errore su `SharedPreferences` (`sync_diag`), per
  sopravvivere al riavvio.
- Notifica ai listener con throttle di 250 ms (una sync completa produce decine
  di eventi ravvicinati).

## 2. Strumentazione di `MqttSyncManager`

| Punto | Evento registrato |
|---|---|
| `connectInternal` | parametri usati; configurazione mancante/disabilitata; client già attivo in attesa di riconnessione |
| `addConnectedListener` | connessione riuscita + timestamp |
| `addDisconnectedListener` | causa, origine (`MqttDisconnectSource`), ritardo e numero di tentativi del reconnector; distingue caduta da tentativo fallito |
| `disconnectInternal` | disconnessione volontaria |
| `subscribeToTopics` | esito di ogni sottoscrizione |
| `publishAll` | riepilogo della sync completa, errori per singolo topic |
| `publish*` | invio riuscito/fallito, invio saltato perché offline |
| `handleVeicolo` / `handleRifornimento` | inserito / aggiornato / eliminato / scartato (payload illeggibile, veicolo mancante); i messaggi già allineati sono aggregati in un unico evento |

Aggiunte all'API pubblica: `forceReconnect()` e `getClientStateLabelRes()`
(stato interno del client HiveMQ tradotto in risorsa stringa).

Refactoring per evitare duplicazione: i quattro metodi `publish*` confluiscono in
un unico `publish(tipo, id, json, descrizione, toastResId)`; le due
sottoscrizioni in `subscribe(topicFilter, descrizione)`.

## 3. Vista (`SyncStatusActivity`)

`activity_sync_status.xml`:
- card stato: icona + stato, riepilogo configurazione, ultimo errore (in rosso,
  nascosto se assente);
- card contatori: inviati / ricevuti / falliti / cadute;
- pulsante "Riconnetti ora";
- `RecyclerView` degli eventi (più recenti in cima), `item_sync_log.xml` con
  barra colorata per livello, ora, categoria, messaggio;
- menu toolbar: condividi log (file di testo via `FileProvider`), svuota log
  (con conferma).

La vista si aggiorna da sola registrandosi come listener di `SyncLog`; non tocca
il listener di connessione già usato da `MainActivity`. Lo scroll resta fermo se
l'utente sta leggendo eventi più vecchi.

## 4. Accesso

- Voce "Diagnostica sync" nel drawer.
- L'icona di stato sync nella toolbar apre la vista (prima mostrava un toast).

## 5. Risorse

Stringhe `sync_*`, colori `log_info`/`log_warn`/`log_error`, stili
`SyncCounter*`, `menu_sync_status.xml`, activity nel manifest.

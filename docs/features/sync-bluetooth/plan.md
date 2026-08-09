# Piano di implementazione — Sync Bluetooth

## Scelte tecniche

### Bluetooth Classic RFCOMM

`BluetoothServerSocket` / `BluetoothSocket` su un UUID applicativo fisso, non BLE. Entrambi i peer
sono Android, il volume è di qualche centinaio di KB di JSON e il socket *secure* fornisce cifratura
e bonding a livello di sistema operativo. BLE GATT richiederebbe chunking su MTU da 20–512 byte
senza alcun vantaggio. Nessuna dipendenza nuova in `app/build.gradle`.

### Identità delle auto

La stessa auto fisica ha UUID diversi sui due telefoni finché non si sono mai parlati. Quando il
ricevente sceglie «collega a un'auto che ho già», **adotta l'UUID del mittente**: inserisce il
veicolo remoto, ripunta i propri rifornimenti sul nuovo id, elimina il vecchio veicolo — il tutto in
transazione. Da quel momento le due installazioni usano lo stesso id per quell'auto e ogni sessione
successiva combacia da sola, senza tabelle di mapping da persistere (coerente con la scelta
"one-shot").

L'ordine delle operazioni è vincolante: il nuovo veicolo va inserito **prima** di ripuntare i
rifornimenti (vincolo di foreign key) e il vecchio va eliminato **dopo** (altrimenti la
`ON DELETE CASCADE` porterebbe via i rifornimenti).

### Nessuna modifica allo schema

La versione del database resta 5. Si aggiungono solo query ai DAO esistenti.

## Protocollo

Frame binari sul socket: `int` big-endian con la lunghezza + JSON UTF-8
(`DataOutputStream.writeInt()` / `DataInputStream.readInt()` + `readFully()`), con un tetto di 8 MB
per frame a difesa da payload malformati.

```
 CLIENT (chi propone)                        SERVER (chi attende)
   HELLO       {protocol, deviceName, nonce}   →
                                              ←  HELLO_ACK {deviceName, nonce}
   ── entrambi calcolano lo stesso codice a 6 cifre e lo mostrano ──
   CONFIRM     {ok}                            ⇄  CONFIRM {ok}
   OFFER       {veicoli:[id, nome, targa, updatedAt,
                         nRifornimenti, primo, ultimo]}  →
                                              ←  ACCEPT {accettati:[{remoteId, localId|null}]}
   DATA        {veicoli[], rifornimenti[]}     →
                                                 (adotta gli id, applica il merge,
                                                  poi raccoglie i propri dati)
                                              ←  DATA {veicoli[], rifornimenti[]}
   DONE        {conteggi}                      ⇄  DONE {conteggi}
```

L'ordine non è casuale: il server deve adottare gli id **prima** di rispondere, così i suoi
rifornimenti viaggiano già con il `veicolo_id` condiviso e il client li applica senza rimappature.

Il codice di verifica è `SHA-256(min(nonceA,nonceB) ‖ max(nonceA,nonceB))`, primi 3 byte modulo
10^6, con zero-padding a 6 cifre. L'ordinamento dei nonce rende il calcolo indipendente dal ruolo.
Il codice protegge dal collegarsi al telefono sbagliato in una stanza affollata; la riservatezza del
canale è già garantita dal socket RFCOMM secure.

Se una delle due parti rifiuta (codice diverso, lista non accettata, annullamento) invia
`REJECT {motivo}` e chiude: nessuna scrittura sul database da nessuna delle due parti.

## Merge

Semantica identica a quella MQTT già collaudata, estratta in una classe condivisa:

- **Veicolo**: insert se l'id manca, update se `remote.updatedAt > local.updatedAt`, altrimenti
  nessuna operazione.
- **Rifornimento**: scartato se il veicolo di riferimento non esiste in locale, altrimenti insert o
  update con la stessa regola.
- **Mai una cancellazione.**

## File

### Nuovi

| File | Ruolo |
|---|---|
| `SyncMerger.java` | Merge condiviso con esito (`INSERITO`, `AGGIORNATO`, `GIA_ALLINEATO`, `SCARTATO_FK`), estratto da `MqttSyncManager`, più l'adozione transazionale dell'id veicolo |
| `BtMessages.java` | DTO Gson dei messaggi e helper del codice a 6 cifre |
| `BluetoothSyncManager.java` | Macchina a stati della sessione: accept/connect thread, framing, protocollo, watchdog dei timeout, callback sul main thread |
| `BluetoothSyncActivity.java` | UI dell'intero flusso |
| `VeicoloSelectAdapter.java` | Lista auto con checkbox (mittente) |
| `BtOfferAdapter.java` | Lista auto proposte: checkbox + destinazione (ricevente) |
| `BluetoothDeviceAdapter.java` | Scelta del dispositivo |
| `activity_bluetooth_sync.xml`, `item_veicolo_select.xml`, `item_bt_offer.xml`, `item_bt_device.xml`, `dialog_bt_code.xml` | Layout |
| `ic_bluetooth.xml` | Icona per il drawer |

### Modificati

- **`AndroidManifest.xml`** — `BLUETOOTH_CONNECT` e `BLUETOOTH_SCAN` (quest'ultimo con
  `usesPermissionFlags="neverForLocation"`), `BLUETOOTH` e `BLUETOOTH_ADMIN` con
  `maxSdkVersion="30"`; registrazione di `BluetoothSyncActivity`. `ACCESS_FINE_LOCATION`, che serve
  alla discovery su API ≤ 30, è già dichiarato.
- **`nav_menu.xml` + `MainActivity.java`** — voce «Sincronizza via Bluetooth».
- **`VeicoloDao.java`** — `getByIds(List<String>)`.
- **`RifornimentoDao.java`** — `getByVeicoli(List<String>)`, `statsByVeicoli(List<String>)`
  (COUNT/MIN/MAX per costruire l'OFFER senza caricare i dati), `reassignVeicolo(vecchio, nuovo)`.
- **`MqttSyncManager.java`** — usa `SyncMerger` al posto della logica inline duplicata; `publishAll()`
  esposto come `republishAll()` per il riallineamento post-sessione.
- **`SyncLog.java`** — nuova categoria `CAT_BT`.
- **`strings.xml`** — stringhe della feature.

## Flusso UI

**Schermata iniziale**: due scelte, «Condividi le mie auto» e «Ricevi da un altro telefono», una
riga di stato e un pulsante Annulla disponibile durante la sessione.

**Ramo mittente**: multi-selezione delle auto → lista dispositivi (prima gli accoppiati, poi quelli
trovati con `startDiscovery()`) → `cancelDiscovery()` e connessione → dialog del codice → attesa
dell'accettazione → progresso → riepilogo.

**Ramo ricevente**: richiesta di visibilità (`ACTION_REQUEST_DISCOVERABLE`, 120 s, che accende anche
il Bluetooth se spento) → attesa → dialog del codice → schermata di accettazione, con per ogni auto
proposta checkbox, dati e selettore di destinazione:

- *Crea nuova auto* — default in assenza di corrispondenze;
- *Collega a «…»* — preselezionato se una targa locale normalizzata (maiuscole, senza separatori)
  coincide, o in seconda battuta se coincide il nome;
- *Già condivisa* — riga informativa non modificabile quando l'id remoto esiste già in locale.

→ progresso → riepilogo.

**Robustezza**: ogni fase ha un watchdog (120 s per le fasi interattive, 60 s per il trasferimento)
che chiude il socket sbloccando la lettura con `IOException`; `cancelSession()` fa lo stesso su
richiesta dell'utente o alla chiusura dell'activity. Ogni evento finisce in `SyncLog` con categoria
`BT`.

**Dopo la sessione**, se MQTT è connesso: tombstone sugli id dei veicoli sostituiti dall'adozione e
`republishAll()`, così il broker non rimanda il vecchio veicolo dal messaggio retained.

## Ordine di lavoro

1. `SyncMerger` + aggancio in `MqttSyncManager` (MQTT deve restare identico).
2. DAO, permessi, voce di menu, stringhe.
3. `BtMessages` + `BluetoothSyncManager`.
4. `BluetoothSyncActivity` e adapter, entrambi i rami.
5. Riallineamento MQTT e logging.
6. Compilazione e prove sui due dispositivi.

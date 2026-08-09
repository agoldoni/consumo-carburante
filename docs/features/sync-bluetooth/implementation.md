# Implementazione — Sync Bluetooth

## Cosa è stato fatto

Sincronizzazione punto a punto delle auto in comune fra due installazioni dell'app, su Bluetooth
Classic (RFCOMM), raggiungibile dal drawer alla voce **Sync Bluetooth**. Una sessione è one-shot,
bidirezionale e interamente guidata dall'utente: chi la avvia propone un elenco di auto, chi la
riceve conferma un codice a 6 cifre e sceglie quali accettare; al termine i due telefoni hanno lo
stesso storico per le auto concordate.

Il canale MQTT esistente non è stato toccato nel comportamento: è stato solo rifattorizzato per
condividere con il Bluetooth la logica di merge.

## File

### Nuovi

| File | Contenuto |
|---|---|
| [SyncMerger.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/SyncMerger.java) | Merge condiviso fra i due canali, con esito tipizzato, e adozione transazionale dell'id veicolo |
| [BtMessages.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/BtMessages.java) | DTO Gson del protocollo e calcolo del codice di verifica |
| [BluetoothSyncManager.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/BluetoothSyncManager.java) | Connessione, protocollo, watchdog, merge, riallineamento MQTT |
| [BluetoothSyncActivity.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/BluetoothSyncActivity.java) | Il flusso completo lato utente |
| [VeicoloSelectAdapter.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/VeicoloSelectAdapter.java) | Auto con casella di selezione (mittente) |
| [BtOfferAdapter.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/BtOfferAdapter.java) | Auto proposte con accettazione e destinazione (ricevente) |
| [BluetoothDeviceAdapter.java](../../../app/src/main/java/it/agoldoni/consumocarburanti/BluetoothDeviceAdapter.java) | Elenco dispositivi |
| `activity_bluetooth_sync.xml`, `item_veicolo_select.xml`, `item_bt_offer.xml`, `item_bt_device.xml`, `dialog_bt_code.xml`, `ic_bluetooth.xml` | Risorse |

### Modificati

- **`AndroidManifest.xml`** — permessi `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (con
  `neverForLocation`) e, con `maxSdkVersion="30"`, i vecchi `BLUETOOTH` / `BLUETOOTH_ADMIN`;
  registrazione dell'activity con `configChanges` su orientamento (la sessione vive nel manager e
  non sopravvivrebbe alla ricreazione dell'activity).
- **`nav_menu.xml`**, **`MainActivity.java`** — nuova voce di menu.
- **`VeicoloDao.java`** — `getByIds`.
- **`RifornimentoDao.java`** — `getByVeicoli`, `statsByVeicoli` (COUNT/MIN/MAX per l'offerta),
  `reassignVeicolo`, POJO `Stats`.
- **`MqttSyncManager.java`** — usa `SyncMerger` al posto della logica di merge inline; nuovo
  `republishAll()`.
- **`SyncLog.java`** — categoria `CAT_BT`.
- **`strings.xml`** — stringhe e plurals della feature.

**Nessuna modifica allo schema del database**: la versione Room resta 5, nessuna migration.

## Decisioni prese

### Bluetooth Classic invece di BLE

`BluetoothServerSocket` / `BluetoothSocket` su un UUID di servizio fisso. Entrambi i peer sono
Android, il payload è di qualche centinaio di KB di JSON e il socket *secure* dà cifratura e
bonding a livello di sistema. BLE GATT avrebbe richiesto chunking su MTU da 20–512 byte senza alcun
vantaggio. Nessuna dipendenza nuova.

### Adozione dell'identificativo per le auto già presenti

È il nodo della feature: la stessa auto fisica ha UUID diversi sui due telefoni finché non si sono
mai parlati, e un merge per id creerebbe un doppione. Quando il ricevente sceglie «collega a
un'auto che ho già», la sua auto **adotta l'id del mittente**
(`SyncMerger.adottaIdVeicolo`): inserimento del veicolo con l'id remoto, ripuntamento dei
rifornimenti, eliminazione del vecchio, tutto in una transazione.

L'ordine è vincolante: il nuovo veicolo va inserito prima di ripuntare i rifornimenti (foreign key)
e il vecchio va eliminato dopo, quando non ha più figli, altrimenti la `ON DELETE CASCADE` porta via
lo storico.

Da quel momento le due installazioni indicano quell'auto con lo stesso id e ogni sessione
successiva combacia da sola: nessuna tabella di mapping da persistere, coerentemente con la scelta
"one-shot". Nome e targa dell'auto risultante sono i più recenti fra i due (`SyncMerger.fondi`), così
il collegamento non fa perdere una modifica fatta in locale.

`reassignVeicolo` aggiorna anche `updatedAt`: il record è cambiato davvero, ed è così che il nuovo
`veicolo_id` si propaga agli altri canali di sincronizzazione.

### Protocollo

Frame binari: `int` big-endian con la lunghezza + JSON UTF-8, con un tetto di 8 MB per frame a
difesa da payload malformati.

```
 MITTENTE                                    RICEVENTE
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

L'ordine non è casuale: il ricevente adotta gli id **prima** di rispondere, così i suoi rifornimenti
partono già con il `veicolo_id` condiviso e il mittente li applica senza rimappature.

Il codice di verifica è `SHA-256(min(nonceA,nonceB) ‖ max(nonceA,nonceB))`, primi 3 byte modulo
10^6. L'ordinamento dei nonce rende il calcolo indipendente dal ruolo. Serve a scoprire di essersi
collegati al telefono sbagliato, non a proteggere il canale: la riservatezza la garantisce già il
socket RFCOMM secure.

Un rifiuto (`REJECT`) o una lista vuota chiudono la sessione senza alcuna scrittura sul database di
nessuna delle due parti.

### Timeout e interruzioni

`BluetoothSocket` non espone un timeout di lettura: l'unico modo per sbloccare una `read` che non
arriverà mai è chiudere il socket da un altro thread, cosa che la fa terminare con `IOException`. Da
qui il watchdog su `ScheduledExecutorService`, armato prima di ogni lettura bloccante e disarmato
subito dopo.

Tre soglie diverse:

- **120 s** per `accept()`, `connect()` e handshake (e per le decisioni dell'utente locale, via
  `poll` sulle code);
- **180 s** per i messaggi che arrivano solo dopo una decisione dell'utente **remoto** (`CONFIRM`,
  `OFFER`, `ACCEPT`): devono essere più lunghi del tempo che quell'utente ha a disposizione,
  altrimenti scadrebbero proprio mentre sta rispondendo;
- **120 s** per il trasferimento, che comprende anche il tempo che l'altro lato impiega a scrivere i
  dati ricevuti prima di rispondere.

Il campo `interruzione` distingue una `IOException` da guasto reale da quella causata da una
chiusura decisa da noi (annullamento o timeout): senza questa distinzione l'utente vedrebbe un
errore per una sua stessa scelta.

### Merge in una sola transazione

`applica()` avvolge i cicli su veicoli e rifornimenti in `db.runInTransaction`. Con qualche migliaio
di rifornimenti la differenza fra una transazione per riga e una sola è fra secondi e minuti — e
l'altro lato sta aspettando la risposta con un watchdog armato.

### Riallineamento MQTT

Se la sincronizzazione MQTT è connessa, al termine di una sessione Bluetooth si chiama
`republishAll()` e si pubblica il tombstone per gli id dei veicoli sostituiti dall'adozione:
altrimenti il messaggio retained del vecchio veicolo lo farebbe ricomparire. È best-effort: a MQTT
disconnesso non si fa nulla, ci pensa la sync completa alla prossima riconnessione.

### Estrazione di `SyncMerger`

La logica di merge era inline in `MqttSyncManager` e serviva identica al Bluetooth. È stata estratta
con un esito tipizzato (`INSERITO` / `AGGIORNATO` / `GIA_ALLINEATO` / `SCARTATO_FK`); i toast e le
voci di diario restano in `MqttSyncManager`, pilotati dall'esito. Il comportamento MQTT è invariato.

## Verifica svolta

Compilazione (`./build.sh debug`) e `lintDebug` puliti: nessun errore, solo warning già presenti
nello stile della codebase (`notifyDataSetChanged`, overdraw sugli item di lista).

Prove su dispositivo reale, ramo per ramo:

- il drawer apre la nuova schermata e mostra la scelta del ruolo;
- **Condividi** → richiesta dei permessi `BLUETOOTH_CONNECT`/`SCAN` (Android 12+), poi l'elenco
  delle auto con targa e numero di rifornimenti, con «Avanti» disabilitato finché non se ne
  seleziona una; poi l'elenco dei dispositivi accoppiati;
- **Ricevi** → richiesta di visibilità per 120 s, poi la schermata di attesa;
- annullamento dalla schermata di attesa: ritorno pulito alla scelta del ruolo, nessun crash, e nel
  diario di diagnostica compaiono le voci `BT` attese (`In attesa di una connessione in ingresso`,
  `Sessione annullata dall'utente`, `Sessione non completata`).

### L'emulatore non serve come secondo dispositivo

Tentativo documentato perché non è ovvio a priori. L'emulatore Android (36.3.10) **ha** un
controller Bluetooth emulato: `netsimd` con Rootcanal, condiviso fra tutte le istanze sullo stesso
host, e i log dell'emulatore confermano `Activated packet streamer for bluetooth emulation`. Due
AVD distinti ottengono indirizzi diversi (`…17:00` e `…17:01`) e lo stack Classic gira davvero: si
vede la registrazione dei PSM L2CAP e la generazione dell'EIR.

Ma l'inquiry BR/EDR fra le due istanze non restituisce nulla: con il ricevente in
`SCAN_MODE_CONNECTABLE_DISCOVERABLE` verificato da `dumpsys`, la ricerca del mittente dura i suoi
13 secondi e chiude con `bta_dm_search_cmpl: No BLE connection, processing classic results` e zero
dispositivi.

Avviando `netsimd` a mano con i log su stderr si vede che **il problema non è nell'app né nello
stack Android**: Rootcanal riceve correttamente i comandi delle due parti —

```
root-canal  ... 1  << Write Scan Enable
root-canal  ... 1     scan_enable=INQUIRY_AND_PAGE_SCAN      <- il ricevente è visibile
root-canal  ... 0  << Inquiry
root-canal  ... 0     inquiry_length=10                       <- il mittente cerca
root-canal  ... inquiry timeout triggered                     <- nessuna risposta
```

L'ipotesi che fosse la distanza simulata è stata verificata e **smentita**: `netsimd` modella l'RSSI
anche per `BT_CLASSIC`, ma riavviandolo con `--rssi=bt_classic:-40 --rssi=ble:-40` l'esito non
cambia. Il log spiega perché: la tabella dei collegamenti contiene solo

```
Set RSSI between sender 0 and receiver 0 on BluetoothClassic: -40
```

cioè il solo dispositivo 0 verso sé stesso, creato all'avvio di netsimd quando ancora non c'era
nessun emulatore. Quando la seconda istanza si registra, il collegamento 0↔1 non viene creato: le
due radio emulate non sono mai messe in comunicazione sul medium.

Da tenere presente anche che **emulatore ↔ telefono fisico è impossibile per costruzione**: netsim è
una radio virtuale, non è ponte verso l'RF reale.

Sui due emulatori si è comunque validato, su uno stack Android vero:

- i permessi runtime `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` su Android 13;
- la richiesta di visibilità per 120 s e il passaggio in ascolto;
- l'avvio della discovery lato mittente (`startDiscovery()` accettata, inquiry effettivamente
  eseguita dallo stack);
- **il watchdog**: allo scadere dei 120 s di attesa connessione il ricevente ha mostrato
  «Tempo scaduto durante: attesa della connessione» ed è tornato pulito alla scelta del ruolo;
- nessun crash in nessuno dei due rami.

**Non verificato**: handshake, codice di verifica, accettazione, adozione dell'id, scambio dei dati,
idempotenza e interruzioni a metà trasferimento — servono due dispositivi fisici. E la non
regressione MQTT, che richiede un broker configurato. Gli scenari da coprire sono elencati qui
sotto.

Se servisse coprire il protocollo senza due telefoni, la strada è rendere iniettabile il trasporto
(oggi `BluetoothSyncManager` prende i suoi stream dal socket): con una coppia di stream collegati si
possono far girare i due ruoli nello stesso processo e verificare handshake, codice, offerta,
accettazione, adozione e merge in un test strumentato.

### Scenari da provare con due telefoni

1. **Regressione MQTT** — con broker configurato, creare veicolo e rifornimento su un telefono e
   verificare che arrivino sull'altro come prima.
2. **Auto nuova** — A condivide un'auto che B non ha, B sceglie «Crea nuova».
3. **Collegamento con adozione dell'id** — stessa targa ma UUID diversi e rifornimenti diversi: a
   fine sessione entrambi hanno l'unione, e su B lo storico è ancora tutto lì (nessuna cascade
   andata a segno).
4. **Multi-selezione** — A propone 3 auto, B ne accetta 2: la terza non compare da nessuna parte.
5. **Rifiuto** — annullare al dialog del codice o alla lista: nessuna scrittura su entrambi i lati.
6. **Idempotenza** — ripetere subito lo scenario 3: zero inserimenti.
7. **Interruzioni** — allontanare i telefoni o spegnere il Bluetooth a metà trasferimento.
8. **Permessi** — rifiutarli su Android 12+, e ricontrollare su un Android 11 o inferiore.

## Limiti noti

- **Il merge non propaga le cancellazioni.** Un rifornimento cancellato su un telefono e ancora
  presente sull'altro ritorna alla sessione successiva.
- **Solo l'iniziatore sceglie le auto.** Se anche il ricevente vuole proporne di sue, avvia una
  sessione a ruoli invertiti.
- **Nessun appaiamento memorizzato** (scelta esplicita): selezione, codice e accettazione si rifanno
  a ogni sessione.
- **L'adozione dell'UUID cambia l'id dell'auto sul ricevente**: riferimenti esterni a quell'id, per
  esempio un CSV esportato in precedenza, non combaciano più.
- **Il ricevente rispedisce anche i dati appena ricevuti.** `raccogli()` rilegge dal database dopo
  il merge, quindi il pacchetto di ritorno contiene pure i rifornimenti che il mittente aveva appena
  inviato. È intenzionale — rende lo scambio idempotente e convergente anche se il primo invio è
  stato parziale — al prezzo di un pacchetto di ritorno più grosso.
- **Se l'app va in background durante la sessione** la callback resta agganciata (viene rilasciata
  solo in `onDestroy`) proprio perché il sistema può mettere l'activity in pausa per la finestra di
  accoppiamento Bluetooth; ma se l'utente esce davvero, la sessione scade sui timeout.
- **La discovery su Android 11 e precedenti** richiede anche i servizi di localizzazione attivi, non
  solo il permesso: la condizione non è verificata esplicitamente, si vede solo un elenco vuoto.

# Sincronizzazione auto in comune via Bluetooth

## Richiesta

Poter sincronizzare le auto in comune fra due installazioni dell'app via Bluetooth, con un
meccanismo esplicito di accettazione da parte di chi riceve e la possibilità di specificare
(a selezione multipla) quali auto si vogliono sincronizzare.

## Contesto

L'app dispone già di una sincronizzazione via MQTT (`MqttSyncManager`) che allinea **tutti** i
veicoli e tutti i rifornimenti fra le installazioni che condividono lo stesso `groupId` su un
broker. Richiede internet, un broker configurato e non permette di scegliere cosa condividere.

Il caso d'uso qui è diverso: due persone che condividono una o due auto (l'auto di famiglia) ma
tengono separate le proprie, e che si trovano fisicamente vicine. Serve un canale peer-to-peer,
utilizzabile offline, con scope selettivo per auto.

## Requisiti

### Funzionali

1. **Canale indipendente e aggiuntivo.** Il Bluetooth non sostituisce MQTT né lo modifica: sono due
   canali che coesistono. Deve funzionare anche senza broker e senza connessione internet.

2. **Trasferimento one-shot.** Nessun appaiamento memorizzato fra i dispositivi: ogni sessione è
   autonoma e prevede scelta del dispositivo, selezione delle auto e accettazione.

3. **Sincronizzazione bidirezionale.** Al termine di una sessione entrambi i telefoni devono avere
   lo stesso insieme di rifornimenti per le auto concordate: chi riceve restituisce i propri dati
   relativi a quelle stesse auto.

4. **Selezione multipla lato mittente.** Chi avvia la sessione sceglie da un elenco quali delle
   proprie auto proporre. L'elenco mostra nome, targa e numero di rifornimenti.

5. **Accettazione lato ricevente, in due livelli:**
   - un **codice di verifica a 6 cifre** mostrato identico sui due telefoni, da confrontare a voce,
     con conferma richiesta a entrambi gli utenti prima di procedere;
   - una **lista delle auto proposte con checkbox** (nome, targa, numero di rifornimenti, periodo
     coperto) da cui il ricevente può accettarne solo alcune.

6. **Gestione delle auto già presenti.** Se il ricevente ha già la stessa auto registrata in
   proprio (UUID diverso), deve poter **collegare** l'auto proposta a quella locale invece di
   crearne una nuova, evitando duplicati. Il collegamento va suggerito automaticamente quando la
   targa (o, in seconda battuta, il nome) coincide.

7. **Merge non distruttivo.** L'unione dei dati non deve mai cancellare nulla: si inseriscono i
   record mancanti e si aggiornano quelli più vecchi, con la stessa regola *last-write-wins* su
   `updatedAt` già usata dalla sincronizzazione MQTT.

8. **Tracciabilità.** Gli eventi della sessione devono comparire nel diario di diagnostica già
   esistente (`SyncStatusActivity`).

### Non funzionali

- Nessuna nuova dipendenza esterna.
- Nessuna modifica allo schema del database (nessuna migration).
- minSdk 24: la soluzione deve funzionare dall'API 24 all'API 34, gestendo il cambio del modello
  dei permessi Bluetooth introdotto con Android 12.
- Una sessione interrotta (dispositivi allontanati, Bluetooth spento, app chiusa) non deve
  provocare crash né lasciare il database in uno stato incoerente.

## Fuori ambito

- Modifiche al comportamento della sincronizzazione MQTT.
- Propagazione delle cancellazioni.
- Proposta di auto da parte di chi riceve (per farlo si avvia una sessione a ruoli invertiti).
- Sincronizzazione automatica in background al passaggio dei dispositivi.

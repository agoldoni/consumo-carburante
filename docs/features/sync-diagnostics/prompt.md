# Diagnostica sincronizzazione MQTT

## Richiesta

> «Ogni tanto non funziona il sync via MQTT. Puoi inserire una vista per visualizzare
> in app il problema?»

## Obiettivo

Dare all'utente uno strumento per capire **dal telefono** perché una sincronizzazione
non è andata a buon fine, senza dover collegare il dispositivo e leggere `logcat`.

## Requisiti

1. **Vista dedicata** raggiungibile dall'app, che mostri:
   - stato attuale della connessione MQTT (non solo connesso/disconnesso: anche
     "connessione in corso", "in attesa di riconnessione", "sync non avviata");
   - configurazione in uso (broker, porta, TLS, gruppo, client id) **senza credenziali**;
   - quando è avvenuta l'ultima connessione e l'ultima disconnessione;
   - ultimo errore registrato;
   - contatori: messaggi inviati, ricevuti, invii falliti, cadute di connessione;
   - diario cronologico degli eventi di sync.

2. **Diario persistente**: gli eventi devono sopravvivere alla chiusura dell'app,
   perché il problema è intermittente e viene notato a posteriori.

3. **Eventi da tracciare**: tentativi di connessione e loro esito con causa,
   connessione persa, sottoscrizioni ai topic, pubblicazioni riuscite/fallite,
   pubblicazioni saltate perché offline, messaggi ricevuti e loro effetto sul
   database (inserito / aggiornato / eliminato / scartato e perché).

4. **Azioni disponibili nella vista**: riconnessione manuale, condivisione del log
   (per poterlo analizzare fuori dall'app), svuotamento del diario.

## Vincoli

- Nessuna nuova dipendenza.
- Il diario non deve mai far fallire la sincronizzazione (errori di I/O ignorati).
- Il log non deve essere invaso dagli eventi di routine: ad ogni riconnessione il
  broker rimanda tutti i messaggi *retained*, in gran parte già allineati.
- Le credenziali MQTT non devono comparire né nella vista né nel log condiviso.

# Feature: Sync via MQTT (HiveMQ Cloud)

## Contesto

L'app Consumo Carburanti è puramente locale (Room/SQLite). L'utente vuole che più installazioni possano condividere veicoli e rifornimenti in tempo reale tramite un broker MQTT cloud gratuito (HiveMQ Cloud).

La sync è **opzionale**: se non configurata, l'app funziona come prima.

## Architettura

```
[App A] ──publish──> [HiveMQ Cloud] ──subscribe──> [App B, App C]
         topic: sync/{groupId}/rifornimenti/{id}
         topic: sync/{groupId}/veicoli/{id}
```

- Ogni operazione CRUD locale viene pubblicata come messaggio MQTT con `retain=true`
- I messaggi retained restano sul broker e vengono consegnati a ogni nuovo subscriber → **sync iniziale automatico**
- Per le eliminazioni: publish messaggio vuoto (payload empty) con retain → cancella il retained message dal topic
- QoS 1 (at least once) per garantire consegna
- I duplicati sono gestiti tramite UUID (upsert nel DB locale)

### Topic structure
```
sync/{groupId}/veicoli/{veicoloId}       → JSON veicolo (retained)
sync/{groupId}/rifornimenti/{rifId}      → JSON rifornimento (retained)
```

`groupId` è una stringa condivisa tra le istanze (es. "famiglia-goldoni"), inserita dall'utente nella configurazione.

### Formato payload
```json
{"id":"uuid","datetime":1710500000,"km":45230,"qtaBenzina":35.5,"costo":62.30,"veicoloId":"uuid","latitude":44.647,"longitude":10.925}
```
Per delete: payload vuoto (`byte[0]`) con `retain=true` → il broker rimuove il retained message.

### Perché MQTT e non Telegram/FCM
- **Retained messages**: sync iniziale automatico senza export/import
- **No limiti di backlog**: a differenza di Telegram (24h/100 msg)
- **No service account/OAuth**: a differenza di FCM, basta username/password
- **Real-time push**: subscriber riceve immediatamente
- **Offline**: QoS 1 + persistent session = messaggi consegnati al reconnect
- **Leggero**: client ~500KB vs Firebase SDK ~3-4MB

## Configurazione utente

L'utente configura nell'app (dialog da menu laterale):
- **Broker URL**: es. `ssl://abc123.s1.eu.hivemq.cloud:8883`
- **Username**: creato nel dashboard HiveMQ Cloud
- **Password**: creata nel dashboard HiveMQ Cloud
- **Group ID**: stringa libera condivisa tra le istanze

Salvati in SharedPreferences. Se non configurati, la sync non viene attivata.

## Dipendenze
- `com.hivemq:hivemq-mqtt-client:1.3.3` — client MQTT Java, supporta Android API 24+
- `com.google.code.gson:gson:2.10.1` — serializzazione JSON

## Nuovi file
- `MqttSyncClient.java` — wrapper client MQTT (connect, publish, subscribe)
- `SyncManager.java` — orchestratore sync (push dopo CRUD, merge da subscribe)
- `dialog_mqtt_settings.xml` — layout dialog configurazione

## File modificati
- `build.gradle` — dipendenze
- `AndroidManifest.xml` — permesso INTERNET
- `RifornimentoDao.java` — upsert + getById
- `VeicoloDao.java` — upsert
- `MainActivity.java` — push dopo CRUD, settings dialog, listener
- `VeicoloActivity.java` — push dopo CRUD, listener
- `nav_menu.xml` — voce "Sincronizzazione"
- `strings.xml` — nuove stringhe

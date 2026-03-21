# Feature: Sync Data (MQTT)

## Descrizione

Sincronizzazione dei rifornimenti tramite MQTT usando HiveMQ Cloud. Ogni nuovo rifornimento viene pubblicato automaticamente su un topic MQTT. I dispositivi sottoscritti ricevono i messaggi e inseriscono i rifornimenti nel DB locale, usando gli UUID per la deduplicazione.

## Requisiti

1. **Libreria MQTT**: HiveMQ MQTT Client (`com.hivemq:hivemq-mqtt-client`) + Gson per JSON
2. **Configurazione**: schermata di configurazione per gli estremi del server MQTT (broker URL, porta, username, password, group ID)
3. **Publish**: ogni insert/update di rifornimento viene pubblicato su topic `sync/{groupId}/rifornimenti/{id}`
4. **Subscribe**: ascolto su `sync/{groupId}/rifornimenti/#` per ricevere rifornimenti da altri dispositivi
5. **Veicoli**: publish/subscribe anche su `sync/{groupId}/veicoli/{id}` per sincronizzare i veicoli
6. **Deduplicazione**: UUID usato per evitare duplicati (ignora se gia presente)
7. **QoS 1**: at least once delivery
8. **Retained messages**: per sync iniziale
9. **TLS**: connessione sicura (porta 8883)

## Configurazione

Salvata in SharedPreferences:
- `mqtt_broker_url` (es. `broker.hivemq.com`)
- `mqtt_port` (es. `8883`)
- `mqtt_username`
- `mqtt_password`
- `mqtt_group_id` (per raggruppare i dispositivi)
- `mqtt_enabled` (boolean)

## Piano di implementazione

### Step 1: Dipendenze
- Aggiungere HiveMQ MQTT Client e Gson in build.gradle
- Aggiungere permesso INTERNET in AndroidManifest

### Step 2: MqttConfig
- Classe per gestire la configurazione MQTT (SharedPreferences)

### Step 3: MqttSyncManager
- Classe singleton per gestire connessione, publish e subscribe
- Metodi: connect(), disconnect(), publishRifornimento(), publishVeicolo()
- Callback per messaggi ricevuti: inserisce nel DB locale con deduplicazione

### Step 4: Configurazione UI
- Activity o Dialog per configurare gli estremi MQTT
- Voce nel navigation drawer

### Step 5: Hook nei punti di inserimento
- Dopo insert/update rifornimento in MainActivity: publishRifornimento()
- Dopo insert/update veicolo in VeicoloActivity: publishVeicolo()

### Step 6: Documentazione
- docs/features/sync-data.md

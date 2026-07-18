# Sync Data (MQTT)

## Panoramica

Sincronizzazione dei dati tra dispositivi tramite protocollo MQTT usando HiveMQ Cloud come broker. I rifornimenti e i veicoli vengono pubblicati automaticamente e ricevuti su tutti i dispositivi connessi allo stesso gruppo.

## Come funziona

1. L'utente configura gli estremi del server MQTT dal menu laterale ("Configurazione Sync")
2. Una volta abilitata la sync, ogni rifornimento inserito o modificato viene pubblicato automaticamente
3. I dispositivi sottoscritti allo stesso gruppo ricevono i dati e li inseriscono nel DB locale
4. La deduplicazione tramite UUID evita duplicati

## Configurazione

Accessibile dal menu laterale. Parametri:

| Parametro | Descrizione | Esempio |
|-----------|-------------|---------|
| Broker URL | Indirizzo del server MQTT | `broker.hivemq.com` |
| Porta | Porta TLS | `8883` |
| Username | Credenziali HiveMQ | `myuser` |
| Password | Credenziali HiveMQ | `mypassword` |
| Group ID | Identificativo del gruppo di sync | `famiglia-rossi` |
| Abilitato | Attiva/disattiva la sync | on/off |

## Topics MQTT

- `sync/{groupId}/rifornimenti/{rifornimentoId}` - dati rifornimento (JSON)
- `sync/{groupId}/veicoli/{veicoloId}` - dati veicolo (JSON)

## Formato payload

### Rifornimento
```json
{
  "id": "uuid",
  "datetime": 1710500000,
  "km": 45230,
  "qtaBenzina": 35.5,
  "costo": 62.30,
  "veicoloId": "uuid",
  "latitude": 44.647,
  "longitude": 10.925
}
```

### Veicolo
```json
{
  "id": "uuid",
  "nome": "Fiat Panda",
  "targa": "AB123CD"
}
```

## Dettagli tecnici

- **Libreria**: HiveMQ MQTT Client 1.3.3
- **QoS**: 1 (at least once), sia in publish che in subscribe
- **Retained**: si (per sync iniziale di nuovi dispositivi)
- **TLS**: connessione sicura sulla porta 8883
- **Serializzazione**: Gson
- **Deduplicazione**: UUID del rifornimento/veicolo come chiave
- **Merge**: last-write-wins tramite campo `updatedAt`

## Robustezza della connessione

- **Client ID persistente**: generato una volta e salvato nelle SharedPreferences
  (`MqttConfig.getClientId()`); con `cleanSession(false)` permette al broker di
  riprendere la sessione (subscription e messaggi QoS 1 accodati offline).
- **Automatic reconnect**: il client HiveMQ riconnette da solo con backoff
  esponenziale quando la connessione cade (rete mobile/Wi-Fi, broker riavviato).
- **Listener di connessione**: lo stato `connected` è aggiornato dai listener
  connected/disconnected del client (non più solo al primo connect); ad ogni
  riconnessione vengono rieseguite le subscribe e il full sync (`publishAll`),
  così i retained del broker riallineano ciò che è arrivato mentre si era offline.
- **Esito publish verificato**: i futures dei publish vengono controllati; il toast
  "Sync: inviato…" appare solo se il publish è andato a buon fine, altrimenti
  l'errore finisce nel log.
- **Cambio configurazione**: al salvataggio della configurazione viene chiamato
  `reconfigure()` (disconnect + connect), che applica subito nuovi broker/gruppo
  anche se una connessione era già attiva.

## Notifiche in-app

Ogni messaggio MQTT applicato al DB locale mostra un Toast (stesso pattern
dell'app spese): "Sync: ricevuto rifornimento", "Sync: ricevuto veicolo",
"Sync: eliminato da remoto"; in uscita "Sync: inviato rifornimento" /
"Sync: inviato veicolo".

## Limiti noti (condivisi con il pattern spese)

- Un dispositivo rimasto offline durante una cancellazione può far "risorgere"
  il record cancellato al riconnect (il delete è un retained vuoto che il broker
  rimuove; servirebbero tombstone per eliminarlo davvero su tutti i device).

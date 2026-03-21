# Feature: Sync via Telegram

## Contesto

L'app Consumo Carburanti è puramente locale (Room/SQLite). L'utente vuole che più installazioni possano condividere i dati dei rifornimenti e dei veicoli tramite un canale Telegram.

Ogni istanza dell'app ha il **proprio bot Telegram** (token diverso), tutti admin dello stesso canale. Questo garantisce offset `getUpdates` indipendenti per ogni istanza.

## Architettura

```
[App A - Bot Token A] ──sendMessage──> [Canale Telegram] <──sendMessage── [App B - Bot Token B]
[App A - Bot Token A] <──getUpdates──  [channel_post]    ──getUpdates──> [App B - Bot Token B]
```

- Ogni operazione CRUD locale viene anche inviata al canale come messaggio JSON
- Il pull (`getUpdates`) legge i messaggi dal canale e fa upsert/delete nel DB locale
- I messaggi propri vengono ignorati (match su `from.id` del bot)
- I duplicati vengono gestiti tramite UUID (upsert)

### Formato messaggi

```json
{"action":"upsert","type":"rifornimento","data":{"id":"uuid","datetime":1710500000,"km":45230,"qtaBenzina":35.5,"costo":62.30,"veicoloId":"uuid","latitude":44.647,"longitude":10.925}}
```
```json
{"action":"upsert","type":"veicolo","data":{"id":"uuid","nome":"Punto","targa":"AB123CD"}}
```
```json
{"action":"delete","type":"rifornimento","id":"uuid"}
```

### Limiti noti
- `getUpdates` backlog: max ~24h / 100 messaggi → sync almeno 1 volta al giorno
- Per sync iniziale completo: export/import JSON come fallback

---

## Piano di implementazione

### Step 1: Dipendenze e permessi

**File: `app/build.gradle`**
- Aggiungere `com.google.code.gson:gson:2.10.1`
- Aggiungere `com.squareup.okhttp3:okhttp:4.12.0`

**File: `app/src/main/AndroidManifest.xml`**
- Aggiungere `<uses-permission android:name="android.permission.INTERNET" />`

### Step 2: Modello messaggio sync

**Nuovo file: `app/src/main/java/it/agoldoni/consumocarburanti/TelegramSyncMessage.java`**

Classe POJO con campi:
- `String action` — "upsert" o "delete"
- `String type` — "rifornimento" o "veicolo"
- `JsonObject data` — entity serializzata (per upsert)
- `String id` — UUID del record (per delete)

Metodi factory statici:
- `upsertRifornimento(Rifornimento r)` → crea messaggio
- `upsertVeicolo(Veicolo v)` → crea messaggio
- `deleteRifornimento(String id)` → crea messaggio
- `deleteVeicolo(String id)` → crea messaggio

### Step 3: Client Telegram Bot API

**Nuovo file: `app/src/main/java/it/agoldoni/consumocarburanti/TelegramClient.java`**

Usa OkHttp. Metodi:
- `sendMessage(String text)` → POST `https://api.telegram.org/bot{token}/sendMessage` con `chat_id` e `text`
- `getUpdates(long offset)` → GET `https://api.telegram.org/bot{token}/getUpdates?offset={offset}&allowed_updates=["channel_post"]`
- `getMe()` → GET per verificare validità token (usato nel test connessione)

Bot token e chat ID letti da SharedPreferences. Se non configurati, i metodi sono no-op.

### Step 4: SyncManager

**Nuovo file: `app/src/main/java/it/agoldoni/consumocarburanti/SyncManager.java`**

Orchestratore del sync. Usa `ExecutorService` per operazioni in background (pattern già usato in MainActivity/VeicoloActivity).

**Invio (push):**
- `pushRifornimento(Rifornimento r)` → serializza con Gson → `telegramClient.sendMessage(json)`
- `pushVeicolo(Veicolo v)` → idem
- `pushDeleteRifornimento(String id)` → idem
- `pushDeleteVeicolo(String id)` → idem

**Ricezione (pull):**
- `pull(Runnable onComplete)` → chiama `getUpdates`, per ogni `channel_post`:
  - Skip se `from.id` == proprio bot ID (evita processare propri messaggi)
  - Parse JSON del `text` come `TelegramSyncMessage`
  - Se `action == "upsert"` e `type == "veicolo"`: deserializza → `veicoloDao.upsert()`
  - Se `action == "upsert"` e `type == "rifornimento"`: deserializza → `rifornimentoDao.upsert()`
  - Se `action == "delete"`: crea entity con solo ID → `dao.delete()`
  - Aggiorna offset in SharedPreferences
  - Ordine: prima veicoli poi rifornimenti (per FK)
- Callback `onComplete` per refresh UI

### Step 5: DAO — aggiungere upsert

**File: `app/src/main/java/it/agoldoni/consumocarburanti/RifornimentoDao.java`**
- Aggiungere: `@Insert(onConflict = OnConflictStrategy.REPLACE) void upsert(Rifornimento rifornimento);`
- Aggiungere: `@Query("SELECT * FROM rifornimenti WHERE id = :id") Rifornimento getById(String id);`

**File: `app/src/main/java/it/agoldoni/consumocarburanti/VeicoloDao.java`**
- Aggiungere: `@Insert(onConflict = OnConflictStrategy.REPLACE) void upsert(Veicolo veicolo);`

(VeicoloDao ha già `getById`)

### Step 6: Integrazione UI — MainActivity

**File: `app/src/main/java/it/agoldoni/consumocarburanti/MainActivity.java`**

- Creare istanza `SyncManager` in `onCreate`
- Dopo `rifornimentoDao.insert(r)` in `showAddDialog`: aggiungere `syncManager.pushRifornimento(r)`
- Dopo `rifornimentoDao.update(rifornimento)` in `showEditDialog`: aggiungere `syncManager.pushRifornimento(rifornimento)`
- Dopo `rifornimentoDao.delete(rifornimento)` in `onDelete`: aggiungere `syncManager.pushDeleteRifornimento(rifornimento.getId())`
- Nel listener del NavigationView, aggiungere gestione `R.id.nav_sync`:
  - Chiama `syncManager.pull(() -> { loadVeicoli(); })` con feedback visivo (Toast o Snackbar)
- In `onResume`: opzionale auto-pull

### Step 7: Integrazione UI — VeicoloActivity

**File: `app/src/main/java/it/agoldoni/consumocarburanti/VeicoloActivity.java`**

- Creare istanza `SyncManager` in `onCreate`
- Dopo `dao.insert(veicolo)`: aggiungere `syncManager.pushVeicolo(veicolo)`
- Dopo `dao.update(existing)`: aggiungere `syncManager.pushVeicolo(existing)`
- Dopo `dao.delete(veicolo)`: aggiungere `syncManager.pushDeleteVeicolo(veicolo.getId())`

### Step 8: Menu — aggiungere voci

**File: `app/src/main/res/menu/nav_menu.xml`**
- Aggiungere voce `nav_sync` ("Sincronizza")
- Aggiungere voce `nav_telegram_settings` ("Impostazioni Telegram")

### Step 9: Dialog configurazione Telegram

**File: `app/src/main/java/it/agoldoni/consumocarburanti/MainActivity.java`** (dialog inline, come gli altri)

Dialog con:
- Campo "Bot Token"
- Campo "Chat ID" (es. `@nome_canale` o ID numerico)
- Bottone "Verifica" → chiama `TelegramClient.getMe()`, mostra risultato
- Salva in SharedPreferences con chiavi `telegram_bot_token` e `telegram_chat_id`

**Nuovo file layout: `app/src/main/res/layout/dialog_telegram_settings.xml`**
- 2 TextInputLayout (token, chat ID)

### Step 10: Stringhe

**File: `app/src/main/res/values/strings.xml`**
- `menu_sync` → "Sincronizza"
- `menu_telegram_settings` → "Impostazioni Telegram"
- `telegram_bot_token` → "Bot Token"
- `telegram_chat_id` → "Chat ID canale"
- `telegram_verifica` → "Verifica connessione"
- `telegram_connesso` → "Connessione riuscita"
- `telegram_errore` → "Errore di connessione"
- `sync_completata` → "Sincronizzazione completata"
- `sync_errore` → "Errore durante la sincronizzazione"
- `sync_non_configurato` → "Configura Telegram dal menu laterale"
- `telegram_bot_nome` → "Bot: %s"

---

## File riepilogo

| File | Azione |
|---|---|
| `app/build.gradle` | Modifica — aggiungere Gson + OkHttp |
| `AndroidManifest.xml` | Modifica — aggiungere INTERNET permission |
| `TelegramSyncMessage.java` | **Nuovo** |
| `TelegramClient.java` | **Nuovo** |
| `SyncManager.java` | **Nuovo** |
| `RifornimentoDao.java` | Modifica — aggiungere upsert + getById |
| `VeicoloDao.java` | Modifica — aggiungere upsert |
| `MainActivity.java` | Modifica — push dopo CRUD, pull da menu, settings dialog |
| `VeicoloActivity.java` | Modifica — push dopo CRUD |
| `nav_menu.xml` | Modifica — voci Sincronizza + Impostazioni Telegram |
| `dialog_telegram_settings.xml` | **Nuovo** — layout dialog settings |
| `strings.xml` | Modifica — nuove stringhe |

---

## Verifica

1. **Config**: Creare 2 bot via @BotFather, creare un canale, aggiungere entrambi come admin
2. **Settings**: Configurare token e chat ID su 2 dispositivi/emulatori, verificare con "Verifica connessione"
3. **Push**: Istanza A aggiunge rifornimento → verificare messaggio JSON nel canale Telegram
4. **Pull**: Istanza B clicca "Sincronizza" → il rifornimento appare nel DB di B
5. **Update**: Istanza B modifica il rifornimento → Istanza A sincronizza → vede la modifica
6. **Delete**: Istanza A elimina → Istanza B sincronizza → record rimosso
7. **Duplicati**: Sincronizzare 2 volte → nessun duplicato
8. **Veicoli**: Ripetere i test per i veicoli (upsert + delete + FK cascade)
9. **No config**: Senza token/chatID configurati, l'app funziona normalmente senza errori
10. **Build**: `./gradlew assembleDebug` compila senza errori

package it.agoldoni.consumocarburanti;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.Gson;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MqttSyncManager {

    private static final String TAG = "MqttSyncManager";
    private static volatile MqttSyncManager INSTANCE;

    private final Context appContext;
    private final AppDatabase db;
    private final SyncMerger merger;
    private final MqttConfig config;
    private final SyncLog syncLog;
    private final Gson gson;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private static final long UP_TO_DATE_SUMMARY_DELAY_MS = 1500;

    private Mqtt3AsyncClient client;
    private volatile boolean connected;
    private volatile int upToDateCount;
    private volatile String lastFailureCause;
    private OnSyncDataReceivedListener listener;
    private OnConnectionStateChangedListener connectionListener;

    private final Runnable logUpToDateSummary = new Runnable() {
        @Override
        public void run() {
            int count = upToDateCount;
            upToDateCount = 0;
            if (count > 0) {
                syncLog.info(SyncLog.CAT_RECV, count + " messaggi ricevuti erano già allineati");
            }
        }
    };

    public interface OnSyncDataReceivedListener {
        void onDataReceived();
    }

    public interface OnConnectionStateChangedListener {
        void onConnectionStateChanged(boolean connected);
    }

    private MqttSyncManager(Context context) {
        appContext = context.getApplicationContext();
        db = AppDatabase.getInstance(context);
        merger = new SyncMerger(context);
        config = new MqttConfig(context);
        syncLog = SyncLog.getInstance(context);
        gson = new Gson();
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        connected = false;
    }

    public static MqttSyncManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MqttSyncManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MqttSyncManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public void setOnSyncDataReceivedListener(OnSyncDataReceivedListener listener) {
        this.listener = listener;
    }

    public void setOnConnectionStateChangedListener(OnConnectionStateChangedListener listener) {
        this.connectionListener = listener;
    }

    public void reconnectIfNeeded() {
        executor.execute(() -> {
            if (config.isConfigured() && !connected) {
                connectInternal();
            } else if (!config.isConfigured() && client != null) {
                disconnectInternal();
            }
        });
    }

    public void connect() {
        executor.execute(this::connectInternal);
    }

    public void disconnect() {
        executor.execute(this::disconnectInternal);
    }

    private void connectInternal() {
        if (client != null) {
            // Il client esiste già: se la connessione è caduta ci pensa
            // l'automatic reconnect, non va creato un secondo client.
            if (!connected) {
                syncLog.info(SyncLog.CAT_CONN,
                        "Client già attivo ma non connesso: in attesa della riconnessione automatica");
            }
            return;
        }

        if (!config.isConfigured()) {
            syncLog.warn(SyncLog.CAT_CONN, config.isEnabled()
                    ? "Configurazione incompleta (broker o ID gruppo mancante): sync non avviata"
                    : "Sincronizzazione disabilitata nelle impostazioni");
            return;
        }

        syncLog.info(SyncLog.CAT_CONN, "Connessione a " + config.getBrokerUrl() + ":" + config.getPort()
                + (config.isUseTls() ? " con TLS" : " senza TLS")
                + ", gruppo " + config.getGroupId() + ", client id " + config.getClientId());

        try {
            Mqtt3ClientBuilder builder = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier(config.getClientId())
                    .serverHost(config.getBrokerUrl())
                    .serverPort(config.getPort())
                    .automaticReconnectWithDefaultConfig()
                    .addConnectedListener(context -> {
                        connected = true;
                        lastFailureCause = null;
                        Log.i(TAG, "MQTT connected");
                        syncLog.recordConnected();
                        syncLog.info(SyncLog.CAT_CONN, "Connesso al broker "
                                + config.getBrokerUrl() + ":" + config.getPort());
                        notifyConnectionState(true);
                        // Ad ogni (ri)connessione: riattiva le subscription e
                        // riallinea lo stato (i retained coprono ciò che è arrivato offline)
                        executor.execute(() -> {
                            subscribeToTopics();
                            publishAll();
                        });
                    })
                    .addDisconnectedListener(context -> {
                        boolean wasConnected = connected;
                        connected = false;

                        String cause = SyncLog.describe(context.getCause());
                        String source = context.getSource().name();
                        long retryMs = context.getReconnector().getDelay(TimeUnit.MILLISECONDS);
                        int attempts = context.getReconnector().getAttempts();
                        String retryInfo = context.getReconnector().isReconnect()
                                ? " Nuovo tentativo tra " + (retryMs / 1000) + "s (tentativi: " + attempts + ")."
                                : " Riconnessione automatica non prevista.";

                        if (wasConnected) {
                            Log.w(TAG, "MQTT connection lost: " + cause);
                            syncLog.recordDisconnected();
                            syncLog.error(SyncLog.CAT_CONN,
                                    "Connessione persa (origine " + source + "): " + cause + "." + retryInfo);
                        } else {
                            Log.w(TAG, "MQTT connect attempt failed: " + cause);
                            // Con un broker irraggiungibile i tentativi si ripetono
                            // all'infinito: se la causa non cambia se ne registrano
                            // i primi e poi uno ogni cinque
                            boolean stessaCausa = cause.equals(lastFailureCause);
                            lastFailureCause = cause;
                            if (!stessaCausa || attempts <= 3 || attempts % 5 == 0) {
                                syncLog.warn(SyncLog.CAT_CONN,
                                        "Tentativo di connessione fallito (origine " + source + "): "
                                                + cause + "." + retryInfo);
                            }
                        }
                        notifyConnectionState(false);
                    });

            if (config.isUseTls()) {
                builder.sslWithDefaultConfig();
            }

            client = builder.buildAsync();

            String username = config.getUsername();
            String password = config.getPassword();
            if (!username.isEmpty()) {
                client.connectWith()
                        .cleanSession(false)
                        .simpleAuth()
                        .username(username)
                        .password(password.getBytes(StandardCharsets.UTF_8))
                        .applySimpleAuth()
                        .send()
                        .join();
            } else {
                client.connectWith()
                        .cleanSession(false)
                        .send()
                        .join();
            }

        } catch (Exception e) {
            // Il primo tentativo è fallito ma l'automatic reconnect continua
            // a riprovare in background: il client resta valido.
            Log.e(TAG, "MQTT connection failed", e);
            syncLog.error(SyncLog.CAT_CONN,
                    "Connessione fallita, si riproverà automaticamente", e);
        }
    }

    private void disconnectInternal() {
        if (client != null) {
            try {
                client.disconnect().join();
                syncLog.info(SyncLog.CAT_CONN, "Disconnesso su richiesta dell'app");
            } catch (Exception e) {
                Log.e(TAG, "MQTT disconnect error", e);
                syncLog.error(SyncLog.CAT_CONN, "Errore durante la disconnessione", e);
            }
        }
        connected = false;
        client = null;
        notifyConnectionState(false);
    }

    /**
     * Da chiamare quando la configurazione MQTT viene salvata: chiude la
     * connessione corrente (che usa i vecchi parametri e le vecchie topic)
     * e riconnette con la nuova configurazione.
     */
    public void reconfigure() {
        syncLog.info(SyncLog.CAT_CONN, "Configurazione modificata: riavvio della connessione");
        restart();
    }

    /**
     * Riconnessione forzata richiesta dall'utente dalla vista di diagnostica.
     */
    public void forceReconnect() {
        syncLog.info(SyncLog.CAT_CONN, "Riconnessione manuale richiesta dall'utente");
        restart();
    }

    private void restart() {
        executor.execute(() -> {
            disconnectInternal();
            if (config.isConfigured()) {
                connectInternal();
            }
        });
    }

    private void subscribeToTopics() {
        if (client == null || !connected) return;

        String groupId = config.getGroupId();

        subscribe("sync/" + groupId + "/veicoli/#", "veicoli");
        subscribe("sync/" + groupId + "/rifornimenti/#", "rifornimenti");
    }

    private void subscribe(String topicFilter, String descrizione) {
        client.subscribeWith()
                .topicFilter(topicFilter)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handleIncoming)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Subscribe " + descrizione + " failed", throwable);
                        syncLog.error(SyncLog.CAT_SUB,
                                "Sottoscrizione " + descrizione + " fallita (" + topicFilter + ")", throwable);
                    } else {
                        Log.i(TAG, "Subscribed to " + descrizione + " topic");
                        syncLog.info(SyncLog.CAT_SUB, "Sottoscritto a " + topicFilter);
                    }
                });
    }

    private void handleIncoming(Mqtt3Publish publish) {
        executor.execute(() -> {
            try {
                String topic = publish.getTopic().toString();
                byte[] payload = publish.getPayloadAsBytes();

                if (topic.contains("/veicoli/")) {
                    handleVeicolo(topic, payload);
                } else if (topic.contains("/rifornimenti/")) {
                    handleRifornimento(topic, payload);
                } else {
                    syncLog.warn(SyncLog.CAT_RECV, "Messaggio su topic sconosciuto ignorato: " + topic);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling incoming message", e);
                syncLog.error(SyncLog.CAT_RECV, "Errore nell'elaborazione del messaggio ricevuto", e);
            }
        });
    }

    private void handleVeicolo(String topic, byte[] payload) {
        String id = topic.substring(topic.lastIndexOf('/') + 1);

        if (payload.length == 0) {
            // Delete signal
            Veicolo existing = merger.deleteVeicolo(id);
            if (existing != null) {
                Log.i(TAG, "Deleted veicolo: " + id);
                syncLog.recordReceived();
                syncLog.info(SyncLog.CAT_RECV, "Veicolo eliminato da remoto: " + existing.getNome());
                showToast(R.string.mqtt_eliminato_remoto);
                notifyListener();
            }
            return;
        }

        String json = new String(payload, StandardCharsets.UTF_8);
        Veicolo remote = gson.fromJson(json, Veicolo.class);
        if (remote == null) {
            syncLog.error(SyncLog.CAT_RECV, "Veicolo " + id + " scartato: payload non leggibile");
            return;
        }

        switch (merger.applyVeicolo(remote)) {
            case INSERITO:
                Log.i(TAG, "Inserted veicolo: " + remote.getNome());
                syncLog.recordReceived();
                syncLog.info(SyncLog.CAT_RECV, "Nuovo veicolo ricevuto: " + remote.getNome());
                showToast(R.string.mqtt_ricevuto_veicolo);
                notifyListener();
                break;
            case AGGIORNATO:
                Log.i(TAG, "Updated veicolo: " + remote.getNome());
                syncLog.recordReceived();
                syncLog.info(SyncLog.CAT_RECV, "Veicolo aggiornato da remoto: " + remote.getNome());
                showToast(R.string.mqtt_ricevuto_veicolo);
                notifyListener();
                break;
            default:
                countUpToDate();
                break;
        }
    }

    private void handleRifornimento(String topic, byte[] payload) {
        String id = topic.substring(topic.lastIndexOf('/') + 1);

        if (payload.length == 0) {
            // Delete signal
            Rifornimento existing = merger.deleteRifornimento(id);
            if (existing != null) {
                Log.i(TAG, "Deleted rifornimento: " + id);
                syncLog.recordReceived();
                syncLog.info(SyncLog.CAT_RECV, "Rifornimento eliminato da remoto: " + id);
                showToast(R.string.mqtt_eliminato_remoto);
                notifyListener();
            }
            return;
        }

        String json = new String(payload, StandardCharsets.UTF_8);
        Rifornimento remote = gson.fromJson(json, Rifornimento.class);
        if (remote == null) {
            syncLog.error(SyncLog.CAT_RECV, "Rifornimento " + id + " scartato: payload non leggibile");
            return;
        }

        switch (merger.applyRifornimento(remote)) {
            case SCARTATO_FK:
                Log.w(TAG, "Skipping rifornimento " + id + ": veicolo not found");
                syncLog.warn(SyncLog.CAT_RECV, "Rifornimento " + id + " scartato: veicolo "
                        + remote.getVeicoloId() + " non presente in locale");
                break;
            case INSERITO:
                Log.i(TAG, "Inserted rifornimento: " + id);
                syncLog.recordReceived();
                syncLog.info(SyncLog.CAT_RECV, "Nuovo rifornimento ricevuto: " + id);
                showToast(R.string.mqtt_ricevuto);
                notifyListener();
                break;
            case AGGIORNATO:
                Log.i(TAG, "Updated rifornimento: " + id);
                syncLog.recordReceived();
                syncLog.info(SyncLog.CAT_RECV, "Rifornimento aggiornato da remoto: " + id);
                showToast(R.string.mqtt_ricevuto);
                notifyListener();
                break;
            default:
                countUpToDate();
                break;
        }
    }

    /**
     * Ad ogni riconnessione il broker rimanda tutti i messaggi retained, la
     * maggior parte dei quali già allineati: loggarli uno per uno riempirebbe
     * il diario nascondendo gli eventi utili, quindi se ne logga il totale.
     */
    private void countUpToDate() {
        upToDateCount++;
        mainHandler.removeCallbacks(logUpToDateSummary);
        mainHandler.postDelayed(logUpToDateSummary, UP_TO_DATE_SUMMARY_DELAY_MS);
    }

    private void notifyListener() {
        if (listener != null) {
            mainHandler.post(() -> listener.onDataReceived());
        }
    }

    private void notifyConnectionState(boolean isConnected) {
        if (connectionListener != null) {
            mainHandler.post(() -> connectionListener.onConnectionStateChanged(isConnected));
        }
    }

    private void showToast(int resId) {
        mainHandler.post(() -> Toast.makeText(appContext, resId, Toast.LENGTH_SHORT).show());
    }

    // --- Full sync on reconnect ---

    /**
     * Riallinea il broker con lo stato locale. Serve dopo una sessione di
     * sincronizzazione Bluetooth, che puo' aver introdotto veicoli e
     * rifornimenti che il broker non conosce. Se la connessione non e' attiva
     * non fa nulla: ci pensera' la sync completa alla prossima riconnessione.
     */
    public void republishAll() {
        executor.execute(this::publishAll);
    }

    private void publishAll() {
        if (!connected || client == null) return;

        try {
            String groupId = config.getGroupId();

            List<Veicolo> veicoli = db.veicoloDao().getAll();
            for (Veicolo v : veicoli) {
                publishFullSync("sync/" + groupId + "/veicoli/" + v.getId(), gson.toJson(v), "veicolo");
            }

            List<Rifornimento> rifornimenti = db.rifornimentoDao().getAll();
            for (Rifornimento r : rifornimenti) {
                publishFullSync("sync/" + groupId + "/rifornimenti/" + r.getId(), gson.toJson(r), "rifornimento");
            }

            Log.i(TAG, "Full sync: published " + veicoli.size() + " veicoli, " + rifornimenti.size() + " rifornimenti");
            syncLog.info(SyncLog.CAT_PUB, "Sync completa avviata: " + veicoli.size()
                    + " veicoli e " + rifornimenti.size() + " rifornimenti pubblicati");
        } catch (Exception e) {
            Log.e(TAG, "Full sync failed", e);
            syncLog.error(SyncLog.CAT_PUB, "Sync completa fallita", e);
        }
    }

    private void publishFullSync(String topic, String json, String descrizione) {
        client.publishWith()
                .topic(topic)
                .payload(json.getBytes(StandardCharsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .send()
                .whenComplete((publish, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Full sync publish failed: " + topic, throwable);
                        syncLog.recordFailed();
                        syncLog.error(SyncLog.CAT_PUB,
                                "Sync completa: invio " + descrizione + " " + shortId(topic) + " fallito", throwable);
                    } else {
                        syncLog.recordPublished();
                    }
                });
    }

    private static String shortId(String topic) {
        return topic.substring(topic.lastIndexOf('/') + 1);
    }

    // --- Publish methods ---

    public void publishRifornimento(Rifornimento r) {
        publish("rifornimenti", r.getId(), gson.toJson(r), "rifornimento", R.string.mqtt_inviato);
    }

    public void publishVeicolo(Veicolo v) {
        publish("veicoli", v.getId(), gson.toJson(v), "veicolo", R.string.mqtt_inviato_veicolo);
    }

    public void publishDeleteRifornimento(String id) {
        publish("rifornimenti", id, null, "eliminazione rifornimento", 0);
    }

    public void publishDeleteVeicolo(String id) {
        publish("veicoli", id, null, "eliminazione veicolo", 0);
    }

    /**
     * @param json          payload JSON, oppure null per il messaggio di eliminazione
     * @param toastResId    toast da mostrare a invio riuscito, 0 per nessun toast
     */
    private void publish(String tipo, String id, String json, String descrizione, int toastResId) {
        executor.execute(() -> {
            if (!connected || client == null) {
                Log.w(TAG, "Not connected, " + descrizione + " " + id
                        + " will be published at next reconnect");
                syncLog.warn(SyncLog.CAT_PUB, "Offline: " + descrizione + " " + id
                        + " non inviato, sarà sincronizzato alla prossima connessione");
                return;
            }
            try {
                String topic = "sync/" + config.getGroupId() + "/" + tipo + "/" + id;
                byte[] payload = json != null
                        ? json.getBytes(StandardCharsets.UTF_8)
                        : new byte[0];
                client.publishWith()
                        .topic(topic)
                        .payload(payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Publish " + descrizione + " failed: " + id, throwable);
                                syncLog.recordFailed();
                                syncLog.error(SyncLog.CAT_PUB,
                                        "Invio " + descrizione + " " + id + " fallito", throwable);
                            } else {
                                Log.i(TAG, "Published " + descrizione + ": " + id);
                                syncLog.recordPublished();
                                syncLog.info(SyncLog.CAT_PUB, "Inviato " + descrizione + " " + id);
                                if (toastResId != 0) {
                                    showToast(toastResId);
                                }
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Publish " + descrizione + " failed", e);
                syncLog.recordFailed();
                syncLog.error(SyncLog.CAT_PUB, "Invio " + descrizione + " " + id + " fallito", e);
            }
        });
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Stato interno del client MQTT, mostrato nella diagnostica: distingue una
     * connessione mai avviata da una che sta riprovando in background.
     */
    public int getClientStateLabelRes() {
        Mqtt3AsyncClient current = client;
        if (current == null) return R.string.sync_stato_non_avviato;
        switch (current.getState()) {
            case CONNECTED:
                return R.string.sync_stato_connesso;
            case CONNECTING:
                return R.string.sync_stato_connessione;
            case CONNECTING_RECONNECT:
                return R.string.sync_stato_riconnessione;
            case DISCONNECTED_RECONNECT:
                return R.string.sync_stato_attesa_riconnessione;
            default:
                return R.string.sync_stato_disconnesso;
        }
    }
}

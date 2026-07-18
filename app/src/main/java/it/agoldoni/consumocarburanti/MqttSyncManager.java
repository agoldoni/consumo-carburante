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

public class MqttSyncManager {

    private static final String TAG = "MqttSyncManager";
    private static volatile MqttSyncManager INSTANCE;

    private final Context appContext;
    private final AppDatabase db;
    private final MqttConfig config;
    private final Gson gson;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private Mqtt3AsyncClient client;
    private volatile boolean connected;
    private OnSyncDataReceivedListener listener;
    private OnConnectionStateChangedListener connectionListener;

    public interface OnSyncDataReceivedListener {
        void onDataReceived();
    }

    public interface OnConnectionStateChangedListener {
        void onConnectionStateChanged(boolean connected);
    }

    private MqttSyncManager(Context context) {
        appContext = context.getApplicationContext();
        db = AppDatabase.getInstance(context);
        config = new MqttConfig(context);
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
            return;
        }

        try {
            Mqtt3ClientBuilder builder = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier(config.getClientId())
                    .serverHost(config.getBrokerUrl())
                    .serverPort(config.getPort())
                    .automaticReconnectWithDefaultConfig()
                    .addConnectedListener(context -> {
                        connected = true;
                        Log.i(TAG, "MQTT connected");
                        notifyConnectionState(true);
                        // Ad ogni (ri)connessione: riattiva le subscription e
                        // riallinea lo stato (i retained coprono ciò che è arrivato offline)
                        executor.execute(() -> {
                            subscribeToTopics();
                            publishAll();
                        });
                    })
                    .addDisconnectedListener(context -> {
                        if (connected) {
                            Log.w(TAG, "MQTT connection lost: " + context.getCause().getMessage());
                        }
                        connected = false;
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
        }
    }

    private void disconnectInternal() {
        if (client != null) {
            try {
                client.disconnect().join();
            } catch (Exception e) {
                Log.e(TAG, "MQTT disconnect error", e);
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

        client.subscribeWith()
                .topicFilter("sync/" + groupId + "/veicoli/#")
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handleIncoming)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Subscribe veicoli failed", throwable);
                    } else {
                        Log.i(TAG, "Subscribed to veicoli topic");
                    }
                });

        client.subscribeWith()
                .topicFilter("sync/" + groupId + "/rifornimenti/#")
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::handleIncoming)
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        Log.e(TAG, "Subscribe rifornimenti failed", throwable);
                    } else {
                        Log.i(TAG, "Subscribed to rifornimenti topic");
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
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling incoming message", e);
            }
        });
    }

    private void handleVeicolo(String topic, byte[] payload) {
        String id = topic.substring(topic.lastIndexOf('/') + 1);

        if (payload.length == 0) {
            // Delete signal
            Veicolo existing = db.veicoloDao().getById(id);
            if (existing != null) {
                db.veicoloDao().delete(existing);
                Log.i(TAG, "Deleted veicolo: " + id);
                showToast(R.string.mqtt_eliminato_remoto);
                notifyListener();
            }
            return;
        }

        String json = new String(payload, StandardCharsets.UTF_8);
        Veicolo remote = gson.fromJson(json, Veicolo.class);
        Veicolo local = db.veicoloDao().getById(id);

        if (local == null) {
            db.veicoloDao().insert(remote);
            Log.i(TAG, "Inserted veicolo: " + remote.getNome());
            showToast(R.string.mqtt_ricevuto_veicolo);
            notifyListener();
        } else if (remote.getUpdatedAt() > local.getUpdatedAt()) {
            local.setNome(remote.getNome());
            local.setTarga(remote.getTarga());
            local.setUpdatedAt(remote.getUpdatedAt());
            db.veicoloDao().update(local);
            Log.i(TAG, "Updated veicolo: " + local.getNome());
            showToast(R.string.mqtt_ricevuto_veicolo);
            notifyListener();
        }
    }

    private void handleRifornimento(String topic, byte[] payload) {
        String id = topic.substring(topic.lastIndexOf('/') + 1);

        if (payload.length == 0) {
            // Delete signal
            Rifornimento existing = db.rifornimentoDao().getById(id);
            if (existing != null) {
                db.rifornimentoDao().delete(existing);
                Log.i(TAG, "Deleted rifornimento: " + id);
                showToast(R.string.mqtt_eliminato_remoto);
                notifyListener();
            }
            return;
        }

        String json = new String(payload, StandardCharsets.UTF_8);
        Rifornimento remote = gson.fromJson(json, Rifornimento.class);

        // Check FK: veicolo must exist
        if (remote.getVeicoloId() != null
                && db.veicoloDao().getById(remote.getVeicoloId()) == null) {
            Log.w(TAG, "Skipping rifornimento " + id + ": veicolo not found");
            return;
        }

        Rifornimento local = db.rifornimentoDao().getById(id);

        if (local == null) {
            db.rifornimentoDao().insert(remote);
            Log.i(TAG, "Inserted rifornimento: " + id);
            showToast(R.string.mqtt_ricevuto);
            notifyListener();
        } else if (remote.getUpdatedAt() > local.getUpdatedAt()) {
            local.setDatetime(remote.getDatetime());
            local.setKm(remote.getKm());
            local.setQtaBenzina(remote.getQtaBenzina());
            local.setCosto(remote.getCosto());
            local.setVeicoloId(remote.getVeicoloId());
            local.setLatitude(remote.getLatitude());
            local.setLongitude(remote.getLongitude());
            local.setUpdatedAt(remote.getUpdatedAt());
            db.rifornimentoDao().update(local);
            Log.i(TAG, "Updated rifornimento: " + id);
            showToast(R.string.mqtt_ricevuto);
            notifyListener();
        }
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

    private void publishAll() {
        if (!connected || client == null) return;

        try {
            String groupId = config.getGroupId();

            List<Veicolo> veicoli = db.veicoloDao().getAll();
            for (Veicolo v : veicoli) {
                String topic = "sync/" + groupId + "/veicoli/" + v.getId();
                String json = gson.toJson(v);
                client.publishWith()
                        .topic(topic)
                        .payload(json.getBytes(StandardCharsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Full sync publish veicolo failed: " + topic, throwable);
                            }
                        });
            }

            List<Rifornimento> rifornimenti = db.rifornimentoDao().getAll();
            for (Rifornimento r : rifornimenti) {
                String topic = "sync/" + groupId + "/rifornimenti/" + r.getId();
                String json = gson.toJson(r);
                client.publishWith()
                        .topic(topic)
                        .payload(json.getBytes(StandardCharsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Full sync publish rifornimento failed: " + topic, throwable);
                            }
                        });
            }

            Log.i(TAG, "Full sync: published " + veicoli.size() + " veicoli, " + rifornimenti.size() + " rifornimenti");
        } catch (Exception e) {
            Log.e(TAG, "Full sync failed", e);
        }
    }

    // --- Publish methods ---

    public void publishRifornimento(Rifornimento r) {
        executor.execute(() -> {
            if (!connected || client == null) {
                Log.w(TAG, "Not connected, rifornimento " + r.getId()
                        + " will be published at next reconnect");
                return;
            }
            try {
                String topic = "sync/" + config.getGroupId() + "/rifornimenti/" + r.getId();
                String json = gson.toJson(r);
                client.publishWith()
                        .topic(topic)
                        .payload(json.getBytes(StandardCharsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Publish rifornimento failed: " + r.getId(), throwable);
                            } else {
                                Log.i(TAG, "Published rifornimento: " + r.getId());
                                showToast(R.string.mqtt_inviato);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Publish rifornimento failed", e);
            }
        });
    }

    public void publishVeicolo(Veicolo v) {
        executor.execute(() -> {
            if (!connected || client == null) {
                Log.w(TAG, "Not connected, veicolo " + v.getId()
                        + " will be published at next reconnect");
                return;
            }
            try {
                String topic = "sync/" + config.getGroupId() + "/veicoli/" + v.getId();
                String json = gson.toJson(v);
                client.publishWith()
                        .topic(topic)
                        .payload(json.getBytes(StandardCharsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Publish veicolo failed: " + v.getId(), throwable);
                            } else {
                                Log.i(TAG, "Published veicolo: " + v.getId());
                                showToast(R.string.mqtt_inviato_veicolo);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Publish veicolo failed", e);
            }
        });
    }

    public void publishDeleteRifornimento(String id) {
        executor.execute(() -> {
            if (!connected || client == null) return;
            try {
                String topic = "sync/" + config.getGroupId() + "/rifornimenti/" + id;
                client.publishWith()
                        .topic(topic)
                        .payload(new byte[0])
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Publish delete rifornimento failed: " + id, throwable);
                            } else {
                                Log.i(TAG, "Published delete rifornimento: " + id);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Publish delete rifornimento failed", e);
            }
        });
    }

    public void publishDeleteVeicolo(String id) {
        executor.execute(() -> {
            if (!connected || client == null) return;
            try {
                String topic = "sync/" + config.getGroupId() + "/veicoli/" + id;
                client.publishWith()
                        .topic(topic)
                        .payload(new byte[0])
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .send()
                        .whenComplete((publish, throwable) -> {
                            if (throwable != null) {
                                Log.e(TAG, "Publish delete veicolo failed: " + id, throwable);
                            } else {
                                Log.i(TAG, "Published delete veicolo: " + id);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Publish delete veicolo failed", e);
            }
        });
    }

    public boolean isConnected() {
        return connected;
    }
}

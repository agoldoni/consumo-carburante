package it.agoldoni.consumocarburanti;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MqttConfigActivity extends AppCompatActivity {

    private MqttConfig config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SwitchMaterial switchEnabled;
    private TextInputEditText editBrokerUrl;
    private TextInputEditText editPort;
    private TextInputEditText editUsername;
    private TextInputEditText editPassword;
    private TextInputEditText editGroupId;
    private SwitchMaterial switchTls;
    private TextInputLayout layoutBrokerUrl;
    private TextInputLayout layoutPort;
    private TextInputLayout layoutGroupId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mqtt_config);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        config = new MqttConfig(this);

        switchEnabled = findViewById(R.id.switchEnabled);
        editBrokerUrl = findViewById(R.id.editBrokerUrl);
        editPort = findViewById(R.id.editPort);
        editUsername = findViewById(R.id.editUsername);
        editPassword = findViewById(R.id.editPassword);
        editGroupId = findViewById(R.id.editGroupId);
        switchTls = findViewById(R.id.switchTls);
        layoutBrokerUrl = findViewById(R.id.layoutBrokerUrl);
        layoutPort = findViewById(R.id.layoutPort);
        layoutGroupId = findViewById(R.id.layoutGroupId);

        // Populate from saved config
        switchEnabled.setChecked(config.isEnabled());
        editBrokerUrl.setText(config.getBrokerUrl());
        editPort.setText(String.valueOf(config.getPort()));
        editUsername.setText(config.getUsername());
        editPassword.setText(config.getPassword());
        editGroupId.setText(config.getGroupId());
        switchTls.setChecked(config.isUseTls());

        MaterialButton btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> saveConfig());

        MaterialButton btnTest = findViewById(R.id.btnTest);
        btnTest.setOnClickListener(v -> testConnection());
    }

    private void saveConfig() {
        layoutBrokerUrl.setError(null);
        layoutPort.setError(null);
        layoutGroupId.setError(null);

        String brokerUrl = getText(editBrokerUrl);
        String portStr = getText(editPort);
        String username = getText(editUsername);
        String password = getText(editPassword);
        String groupId = getText(editGroupId);
        boolean enabled = switchEnabled.isChecked();
        boolean useTls = switchTls.isChecked();

        if (brokerUrl.isEmpty()) {
            layoutBrokerUrl.setError(getString(R.string.mqtt_broker_obbligatorio));
            return;
        }
        if (groupId.isEmpty()) {
            layoutGroupId.setError(getString(R.string.mqtt_group_obbligatorio));
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            layoutPort.setError(getString(R.string.mqtt_porta_non_valida));
            return;
        }

        config.save(enabled, brokerUrl, port, username, password, groupId, useTls);
        Toast.makeText(this, R.string.mqtt_config_salvata, Toast.LENGTH_SHORT).show();

        MqttSyncManager.getInstance(this).reconnectIfNeeded();
    }

    private void testConnection() {
        String brokerUrl = getText(editBrokerUrl);
        String portStr = getText(editPort);
        String username = getText(editUsername);
        String password = getText(editPassword);
        boolean useTls = switchTls.isChecked();

        if (brokerUrl.isEmpty()) {
            layoutBrokerUrl.setError(getString(R.string.mqtt_broker_obbligatorio));
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port <= 0 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            layoutPort.setError(getString(R.string.mqtt_porta_non_valida));
            return;
        }

        Toast.makeText(this, "Connessione in corso...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder builder =
                        com.hivemq.client.mqtt.MqttClient.builder()
                        .useMqttVersion3()
                        .identifier("test-" + System.currentTimeMillis())
                        .serverHost(brokerUrl)
                        .serverPort(port);

                if (useTls) {
                    builder.sslWithDefaultConfig();
                }

                com.hivemq.client.mqtt.mqtt3.Mqtt3BlockingClient client = builder.buildBlocking();

                if (!username.isEmpty()) {
                    client.connectWith()
                            .simpleAuth()
                            .username(username)
                            .password(password.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .applySimpleAuth()
                            .send();
                } else {
                    client.connectWith().send();
                }

                client.disconnect();

                runOnUiThread(() -> Toast.makeText(this,
                        R.string.mqtt_connessione_ok, Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.mqtt_connessione_errore, msg),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}

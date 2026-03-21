package it.agoldoni.consumocarburanti;

import android.content.Context;
import android.content.SharedPreferences;

public class MqttConfig {

    private static final String PREFS_NAME = "mqtt_config";
    private static final String KEY_ENABLED = "mqtt_enabled";
    private static final String KEY_BROKER_URL = "mqtt_broker_url";
    private static final String KEY_PORT = "mqtt_port";
    private static final String KEY_USERNAME = "mqtt_username";
    private static final String KEY_PASSWORD = "mqtt_password";
    private static final String KEY_GROUP_ID = "mqtt_group_id";
    private static final String KEY_USE_TLS = "mqtt_use_tls";

    private final SharedPreferences prefs;

    public MqttConfig(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public String getBrokerUrl() {
        return prefs.getString(KEY_BROKER_URL, "");
    }

    public int getPort() {
        return prefs.getInt(KEY_PORT, 8883);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public String getGroupId() {
        return prefs.getString(KEY_GROUP_ID, "");
    }

    public boolean isUseTls() {
        return prefs.getBoolean(KEY_USE_TLS, true);
    }

    public void save(boolean enabled, String brokerUrl, int port,
                     String username, String password, String groupId, boolean useTls) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_BROKER_URL, brokerUrl)
                .putInt(KEY_PORT, port)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putString(KEY_GROUP_ID, groupId)
                .putBoolean(KEY_USE_TLS, useTls)
                .apply();
    }

    public boolean isValid() {
        return !getBrokerUrl().isEmpty() && !getGroupId().isEmpty();
    }

    public boolean isConfigured() {
        return isEnabled() && isValid();
    }
}

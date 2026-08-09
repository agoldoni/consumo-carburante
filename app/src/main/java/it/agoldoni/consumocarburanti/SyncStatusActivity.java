package it.agoldoni.consumocarburanti;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Vista di diagnostica della sincronizzazione MQTT: stato della connessione,
 * contatori e diario degli eventi registrato da {@link SyncLog}. Serve a capire
 * dal telefono perché una sincronizzazione non è andata a buon fine.
 */
public class SyncStatusActivity extends AppCompatActivity implements SyncLog.OnLogChangedListener {

    private SyncLog syncLog;
    private MqttConfig config;
    private SyncLogAdapter adapter;

    private ImageView imageStato;
    private TextView textStato;
    private TextView textConfig;
    private TextView textUltimoErrore;
    private TextView textInviati;
    private TextView textRicevuti;
    private TextView textFalliti;
    private TextView textCadute;
    private TextView textEventiTitolo;
    private TextView emptyView;
    private RecyclerView recyclerLog;
    private LinearLayoutManager layoutManager;

    private final SimpleDateFormat exportFormat =
            new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync_status);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        syncLog = SyncLog.getInstance(this);
        config = new MqttConfig(this);

        imageStato = findViewById(R.id.imageStato);
        textStato = findViewById(R.id.textStato);
        textConfig = findViewById(R.id.textConfig);
        textUltimoErrore = findViewById(R.id.textUltimoErrore);
        textInviati = findViewById(R.id.textInviati);
        textRicevuti = findViewById(R.id.textRicevuti);
        textFalliti = findViewById(R.id.textFalliti);
        textCadute = findViewById(R.id.textCadute);
        textEventiTitolo = findViewById(R.id.textEventiTitolo);
        emptyView = findViewById(R.id.emptyView);

        recyclerLog = findViewById(R.id.recyclerLog);
        layoutManager = new LinearLayoutManager(this);
        recyclerLog.setLayoutManager(layoutManager);
        adapter = new SyncLogAdapter();
        recyclerLog.setAdapter(adapter);

        MaterialButton btnRiconnetti = findViewById(R.id.btnRiconnetti);
        btnRiconnetti.setOnClickListener(v -> {
            MqttSyncManager.getInstance(this).forceReconnect();
            Toast.makeText(this, R.string.sync_riconnessione_avviata, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncLog.addOnLogChangedListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        syncLog.removeOnLogChangedListener(this);
    }

    @Override
    public void onLogChanged() {
        refresh();
    }

    private void refresh() {
        MqttSyncManager manager = MqttSyncManager.getInstance(this);
        boolean connected = manager.isConnected();

        imageStato.setImageResource(connected
                ? R.drawable.ic_sync_connected
                : R.drawable.ic_sync_disconnected);
        textStato.setText(manager.getClientStateLabelRes());
        textStato.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.green_700 : R.color.log_error));

        textConfig.setText(buildConfigSummary());

        SyncLog.Stats stats = syncLog.getStats();
        textInviati.setText(String.valueOf(stats.published));
        textRicevuti.setText(String.valueOf(stats.received));
        textFalliti.setText(String.valueOf(stats.failed));
        textCadute.setText(String.valueOf(stats.disconnections));

        if (stats.lastError != null) {
            textUltimoErrore.setText(getString(R.string.sync_ultimo_errore,
                    relativeTime(stats.lastErrorAt), stats.lastError));
            textUltimoErrore.setVisibility(View.VISIBLE);
        } else {
            textUltimoErrore.setVisibility(View.GONE);
        }

        List<SyncLog.Entry> entries = syncLog.getEntries();
        // Se l'utente sta leggendo eventi più vecchi, un nuovo evento non deve
        // riportarlo in cima alla lista
        boolean wasAtTop = layoutManager.findFirstCompletelyVisibleItemPosition() <= 0;
        adapter.setData(entries);
        textEventiTitolo.setText(getString(R.string.sync_eventi_conteggio, entries.size()));
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (wasAtTop) {
            recyclerLog.scrollToPosition(0);
        }
    }

    private String buildConfigSummary() {
        StringBuilder sb = new StringBuilder();
        if (!config.isValid()) {
            return getString(R.string.sync_non_configurata);
        }
        if (!config.isEnabled()) {
            sb.append(getString(R.string.sync_disabilitata)).append('\n');
        }
        sb.append(getString(R.string.sync_config_broker,
                config.getBrokerUrl(), config.getPort(),
                getString(config.isUseTls() ? R.string.sync_config_tls : R.string.sync_config_no_tls)));
        sb.append('\n').append(getString(R.string.sync_config_gruppo, config.getGroupId()));
        sb.append('\n').append(getString(R.string.sync_config_client, config.getClientId()));

        SyncLog.Stats stats = syncLog.getStats();
        sb.append('\n').append(getString(R.string.sync_ultima_connessione,
                relativeTime(stats.lastConnectedAt)));
        sb.append('\n').append(getString(R.string.sync_ultima_disconnessione,
                relativeTime(stats.lastDisconnectedAt)));
        return sb.toString();
    }

    private String relativeTime(long timestamp) {
        if (timestamp <= 0) return getString(R.string.sync_mai);
        return DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_sync_status, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_share_log) {
            shareLog();
            return true;
        } else if (item.getItemId() == R.id.action_clear_log) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.sync_conferma_svuota)
                    .setPositiveButton(R.string.elimina, (d, which) -> {
                        syncLog.clear();
                        refresh();
                        Toast.makeText(this, R.string.sync_log_svuotato, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.annulla, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Esporta il diario come file di testo. Le credenziali non vengono incluse. */
    private void shareLog() {
        File file = new File(getCacheDir(), "sync_log.txt");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(buildLogText());
        } catch (IOException e) {
            Toast.makeText(this, R.string.errore_esportazione, Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider", file);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.sync_log_titolo_condivisione));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent,
                getString(R.string.sync_condividi_log)));
    }

    private String buildLogText() {
        SyncLog.Stats stats = syncLog.getStats();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.sync_log_titolo_condivisione)).append('\n');
        sb.append("Generato: ").append(exportFormat.format(new Date())).append('\n');
        sb.append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("Stato: ").append(getString(
                MqttSyncManager.getInstance(this).getClientStateLabelRes())).append('\n');
        sb.append(buildConfigSummary()).append('\n');
        sb.append("Contatori: inviati=").append(stats.published)
                .append(" ricevuti=").append(stats.received)
                .append(" falliti=").append(stats.failed)
                .append(" cadute=").append(stats.disconnections).append('\n');
        if (stats.lastError != null) {
            sb.append("Ultimo errore: ").append(stats.lastError).append('\n');
        }
        sb.append("\n--- Eventi ---\n");
        for (SyncLog.Entry entry : syncLog.getEntries()) {
            sb.append(exportFormat.format(new Date(entry.timestamp)))
                    .append(" [").append(entry.level.name()).append("] ")
                    .append(entry.category).append(": ")
                    .append(entry.message).append('\n');
        }
        return sb.toString();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}

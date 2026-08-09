package it.agoldoni.consumocarburanti;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Guida l'utente attraverso una sessione di sincronizzazione Bluetooth: scelta
 * del ruolo, selezione delle auto, conferma del codice, accettazione della
 * proposta e riepilogo finale.
 *
 * <p>Il protocollo vive tutto in {@link BluetoothSyncManager}: qui si mostrano
 * le sue richieste e gli si restituiscono le decisioni dell'utente.
 */
public class BluetoothSyncActivity extends AppCompatActivity
        implements BluetoothSyncManager.Callback, BluetoothDeviceAdapter.OnDeviceSelezionatoListener {

    private static final int DURATA_VISIBILITA_S = 120;

    private BluetoothSyncManager manager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private View sezioneRuolo;
    private View sezioneAuto;
    private View sezioneDispositivi;
    private View sezioneOfferta;
    private View sezioneStato;
    private TextView textStato;
    private TextView textStatoDettaglio;
    private TextView textOffertaTitolo;
    private ProgressBar progressRicerca;
    private MaterialButton btnScegliDispositivo;

    private VeicoloSelectAdapter autoAdapter;
    private BluetoothDeviceAdapter dispositiviAdapter;
    private BtOfferAdapter offertaAdapter;

    private AlertDialog dialogCodice;
    private List<String> autoDaProporre = new ArrayList<>();
    /** True quando l'utente ha scelto di ricevere: usato dopo i permessi. */
    private boolean intenzioneRicevere;

    private final ActivityResultLauncher<String[]> permessiLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    risultato -> {
                        if (BluetoothSyncManager.permessiConcessi(this)) {
                            proseguiDopoPermessi();
                        } else {
                            Toast.makeText(this, R.string.bt_permessi_negati,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    private final ActivityResultLauncher<Intent> abilitaBtLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    risultato -> {
                        if (bluetoothAttivo()) {
                            proseguiDopoPermessi();
                        } else {
                            Toast.makeText(this, R.string.bt_err_spento,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    private final ActivityResultLauncher<Intent> visibilitaLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    risultato -> {
                        if (risultato.getResultCode() == RESULT_CANCELED) {
                            // Senza visibilita' funziona lo stesso, ma solo se
                            // i due telefoni sono gia' accoppiati fra loro
                            Toast.makeText(this, R.string.bt_visibilita_negata,
                                    Toast.LENGTH_LONG).show();
                        }
                        manager.avviaComeRicevente();
                    });

    private final BroadcastReceiver ricercaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String azione = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(azione)) {
                BluetoothDevice device =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    dispositiviAdapter.aggiungi(device);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(azione)) {
                progressRicerca.setVisibility(View.GONE);
                if (dispositiviAdapter.isEmpty()) {
                    Toast.makeText(BluetoothSyncActivity.this,
                            R.string.bt_nessun_dispositivo, Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_sync);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        manager = BluetoothSyncManager.getInstance(this);

        sezioneRuolo = findViewById(R.id.sezioneRuolo);
        sezioneAuto = findViewById(R.id.sezioneAuto);
        sezioneDispositivi = findViewById(R.id.sezioneDispositivi);
        sezioneOfferta = findViewById(R.id.sezioneOfferta);
        sezioneStato = findViewById(R.id.sezioneStato);
        textStato = findViewById(R.id.textStato);
        textStatoDettaglio = findViewById(R.id.textStatoDettaglio);
        textOffertaTitolo = findViewById(R.id.textOffertaTitolo);
        progressRicerca = findViewById(R.id.progressRicerca);
        btnScegliDispositivo = findViewById(R.id.btnScegliDispositivo);

        autoAdapter = new VeicoloSelectAdapter();
        autoAdapter.setOnSelezioneCambiataListener(quante ->
                btnScegliDispositivo.setEnabled(quante > 0));
        RecyclerView recyclerAuto = findViewById(R.id.recyclerAuto);
        recyclerAuto.setLayoutManager(new LinearLayoutManager(this));
        recyclerAuto.setAdapter(autoAdapter);

        dispositiviAdapter = new BluetoothDeviceAdapter(this);
        RecyclerView recyclerDispositivi = findViewById(R.id.recyclerDispositivi);
        recyclerDispositivi.setLayoutManager(new LinearLayoutManager(this));
        recyclerDispositivi.setAdapter(dispositiviAdapter);

        offertaAdapter = new BtOfferAdapter();
        RecyclerView recyclerOfferta = findViewById(R.id.recyclerOfferta);
        recyclerOfferta.setLayoutManager(new LinearLayoutManager(this));
        recyclerOfferta.setAdapter(offertaAdapter);

        findViewById(R.id.btnCondividi).setOnClickListener(v -> avvia(false));
        findViewById(R.id.btnRicevi).setOnClickListener(v -> avvia(true));
        btnScegliDispositivo.setOnClickListener(v -> mostraDispositivi());
        findViewById(R.id.btnCerca).setOnClickListener(v -> cercaDispositivi());
        findViewById(R.id.btnAccetta).setOnClickListener(v -> rispondiOfferta(true));
        findViewById(R.id.btnRifiuta).setOnClickListener(v -> rispondiOfferta(false));
        findViewById(R.id.btnAnnulla).setOnClickListener(v -> manager.annulla());

        btnScegliDispositivo.setEnabled(false);

        IntentFilter filtro = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filtro.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(this, ricercaReceiver, filtro,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // La callback resta agganciata anche quando l'activity va in pausa: il
        // sistema puo' metterla in secondo piano per la finestra di
        // accoppiamento Bluetooth proprio mentre arriva il codice di verifica.
        manager.setCallback(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        manager.clearCallback(this);
        unregisterReceiver(ricercaReceiver);
        fermaRicerca();
        if (isFinishing()) {
            manager.annulla();
        }
        executor.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (manager.isSessioneAttiva()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.bt_conferma_uscita_titolo)
                    .setMessage(R.string.bt_conferma_uscita)
                    .setPositiveButton(R.string.bt_esci, (d, w) -> {
                        manager.annulla();
                        finish();
                    })
                    .setNegativeButton(R.string.annulla, null)
                    .show();
            return;
        }
        // Fuori da una sessione, Indietro ripercorre i passi della procedura
        if (sezioneDispositivi.getVisibility() == View.VISIBLE) {
            fermaRicerca();
            mostraSezione(sezioneAuto);
            return;
        }
        if (sezioneAuto.getVisibility() == View.VISIBLE) {
            mostraSezione(sezioneRuolo);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    // --- Avvio: permessi, Bluetooth acceso, ruolo ---

    private void avvia(boolean ricevere) {
        intenzioneRicevere = ricevere;
        if (!BluetoothSyncManager.permessiConcessi(this)) {
            permessiLauncher.launch(BluetoothSyncManager.permessiNecessari());
            return;
        }
        proseguiDopoPermessi();
    }

    private void proseguiDopoPermessi() {
        if (BluetoothSyncManager.adattatore(this) == null) {
            Toast.makeText(this, R.string.bt_err_non_supportato, Toast.LENGTH_LONG).show();
            return;
        }
        if (!bluetoothAttivo()) {
            abilitaBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        if (intenzioneRicevere) {
            chiediVisibilita();
        } else {
            mostraSelezioneAuto();
        }
    }

    private boolean bluetoothAttivo() {
        BluetoothAdapter adapter = BluetoothSyncManager.adattatore(this);
        return adapter != null && adapter.isEnabled();
    }

    private void chiediVisibilita() {
        Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DURATA_VISIBILITA_S);
        visibilitaLauncher.launch(intent);
    }

    // --- Ramo mittente ---

    private void mostraSelezioneAuto() {
        executor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(this);
            List<Veicolo> veicoli = db.veicoloDao().getAll();

            List<String> ids = new ArrayList<>();
            for (Veicolo v : veicoli) ids.add(v.getId());
            Map<String, Integer> conteggi = new HashMap<>();
            if (!ids.isEmpty()) {
                for (RifornimentoDao.Stats s : db.rifornimentoDao().statsByVeicoli(ids)) {
                    conteggi.put(s.veicoloId, s.conteggio);
                }
            }

            runOnUiThread(() -> {
                if (veicoli.isEmpty()) {
                    Toast.makeText(this, R.string.bt_nessuna_auto_locale,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                autoAdapter.setData(veicoli, conteggi);
                mostraSezione(sezioneAuto);
            });
        });
    }

    @SuppressLint("MissingPermission")
    private void mostraDispositivi() {
        autoDaProporre = autoAdapter.getSelezionati();
        if (autoDaProporre.isEmpty()) {
            Toast.makeText(this, R.string.bt_seleziona_almeno_una, Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothAdapter adapter = BluetoothSyncManager.adattatore(this);
        List<BluetoothDevice> accoppiati = new ArrayList<>();
        if (adapter != null) {
            try {
                accoppiati.addAll(adapter.getBondedDevices());
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.bt_permessi_negati, Toast.LENGTH_LONG).show();
                return;
            }
        }
        dispositiviAdapter.setData(accoppiati);
        mostraSezione(sezioneDispositivi);
    }

    @SuppressLint("MissingPermission")
    private void cercaDispositivi() {
        BluetoothAdapter adapter = BluetoothSyncManager.adattatore(this);
        if (adapter == null) return;
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
            if (adapter.startDiscovery()) {
                progressRicerca.setVisibility(View.VISIBLE);
            } else {
                Toast.makeText(this, R.string.bt_ricerca_fallita, Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.bt_permessi_negati, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void fermaRicerca() {
        BluetoothAdapter adapter = BluetoothSyncManager.adattatore(this);
        try {
            if (adapter != null && adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // niente da fare: la ricerca si fermera' da sola
        }
        progressRicerca.setVisibility(View.GONE);
    }

    @Override
    public void onDeviceSelezionato(BluetoothDevice device) {
        fermaRicerca();
        manager.avviaComeMittente(device, autoDaProporre);
    }

    // --- Ramo ricevente ---

    private void rispondiOfferta(boolean accetta) {
        List<BtMessages.AutoAccettata> scelte = accetta
                ? offertaAdapter.getScelte()
                : new ArrayList<>();
        if (accetta && scelte.isEmpty()) {
            Toast.makeText(this, R.string.bt_seleziona_almeno_una, Toast.LENGTH_SHORT).show();
            return;
        }
        mostraSezione(sezioneStato);
        manager.rispondiOfferta(scelte);
    }

    // --- Callback del manager ---

    @Override
    public void onFase(BluetoothSyncManager.Fase fase, String dettaglio) {
        mostraSezione(sezioneStato);
        switch (fase) {
            case ATTESA_CONNESSIONE:
                textStato.setText(R.string.bt_stato_attesa_connessione);
                textStatoDettaglio.setText(getString(R.string.bt_stato_visibile_per,
                        DURATA_VISIBILITA_S));
                break;
            case CONNESSIONE:
                textStato.setText(getString(R.string.bt_stato_connessione,
                        dettaglio != null ? dettaglio : ""));
                textStatoDettaglio.setText("");
                break;
            case VERIFICA:
                textStato.setText(R.string.bt_stato_verifica);
                textStatoDettaglio.setText("");
                break;
            case ATTESA_SCELTA:
                textStato.setText(R.string.bt_stato_attesa_scelta);
                textStatoDettaglio.setText("");
                break;
            case TRASFERIMENTO:
                textStato.setText(R.string.bt_stato_trasferimento);
                textStatoDettaglio.setText("");
                break;
            default:
                break;
        }
    }

    @Override
    public void onCodice(String codice, String nomeRemoto) {
        chiudiDialogCodice();

        View vista = getLayoutInflater().inflate(R.layout.dialog_bt_code, null);
        ((TextView) vista.findViewById(R.id.textCodice)).setText(codice);
        ((TextView) vista.findViewById(R.id.textDispositivoRemoto))
                .setText(getString(R.string.bt_codice_con, nomeRemoto));

        dialogCodice = new AlertDialog.Builder(this)
                .setTitle(R.string.bt_codice_titolo)
                .setView(vista)
                .setCancelable(false)
                .setPositiveButton(R.string.bt_codice_coincide,
                        (d, w) -> manager.confermaCodice(true))
                .setNegativeButton(R.string.bt_codice_diverso,
                        (d, w) -> manager.confermaCodice(false))
                .show();
    }

    @Override
    public void onOfferta(List<BtMessages.VeicoloOfferto> offerta) {
        chiudiDialogCodice();
        if (offerta.isEmpty()) {
            Toast.makeText(this, R.string.bt_offerta_vuota, Toast.LENGTH_LONG).show();
            manager.rispondiOfferta(new ArrayList<>());
            return;
        }
        executor.execute(() -> {
            List<Veicolo> locali = AppDatabase.getInstance(this).veicoloDao().getAll();
            runOnUiThread(() -> {
                offertaAdapter.setData(this, offerta, locali);
                textOffertaTitolo.setText(getResources().getQuantityString(
                        R.plurals.bt_offerta_titolo, offerta.size(), offerta.size()));
                mostraSezione(sezioneOfferta);
            });
        });
    }

    @Override
    public void onConclusa(BluetoothSyncManager.Riepilogo riepilogo) {
        chiudiDialogCodice();
        String corpo = getString(R.string.bt_riepilogo_corpo,
                riepilogo.veicoliNuovi,
                riepilogo.veicoliCollegati,
                riepilogo.rifornimentiNuovi,
                riepilogo.rifornimentiAggiornati,
                riepilogo.rifornimentiInviati);
        new AlertDialog.Builder(this)
                .setTitle(R.string.bt_riepilogo_titolo)
                .setMessage(corpo)
                .setPositiveButton(R.string.ok, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    @Override
    public void onErrore(String messaggio) {
        chiudiDialogCodice();
        mostraSezione(sezioneRuolo);
        new AlertDialog.Builder(this)
                .setTitle(R.string.bt_errore_titolo)
                .setMessage(messaggio)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    @Override
    public void onAnnullata(String motivo) {
        chiudiDialogCodice();
        mostraSezione(sezioneRuolo);
        Toast.makeText(this, motivo, Toast.LENGTH_LONG).show();
    }

    // --- Utilita' di navigazione ---

    private void chiudiDialogCodice() {
        if (dialogCodice != null && dialogCodice.isShowing()) {
            dialogCodice.dismiss();
        }
        dialogCodice = null;
    }

    private void mostraSezione(View sezione) {
        sezioneRuolo.setVisibility(sezione == sezioneRuolo ? View.VISIBLE : View.GONE);
        sezioneAuto.setVisibility(sezione == sezioneAuto ? View.VISIBLE : View.GONE);
        sezioneDispositivi.setVisibility(sezione == sezioneDispositivi ? View.VISIBLE : View.GONE);
        sezioneOfferta.setVisibility(sezione == sezioneOfferta ? View.VISIBLE : View.GONE);
        sezioneStato.setVisibility(sezione == sezioneStato ? View.VISIBLE : View.GONE);
    }
}

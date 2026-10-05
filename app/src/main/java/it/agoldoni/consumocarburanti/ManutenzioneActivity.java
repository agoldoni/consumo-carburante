package it.agoldoni.consumocarburanti;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Elenco degli interventi di manutenzione di un'auto, con aggiunta, modifica ed
 * eliminazione. Ha un proprio selettore dell'auto: cambiarla qui non tocca la
 * scelta della schermata principale, che arriva solo come valore iniziale.
 */
public class ManutenzioneActivity extends AppCompatActivity {

    /** Id dell'auto da mostrare all'apertura. */
    public static final String EXTRA_VEICOLO_ID = "veicoloId";

    private static final String STATE_VEICOLO_ID = "selectedVeicoloId";

    private ManutenzioneDao manutenzioneDao;
    private VeicoloDao veicoloDao;
    private ManutenzioneAdapter adapter;
    private TextView emptyView;
    private FloatingActionButton fab;
    private Spinner spinnerVeicolo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String selectedVeicoloId;
    private List<Veicolo> veicoli;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manutenzione);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        AppDatabase db = AppDatabase.getInstance(this);
        manutenzioneDao = db.manutenzioneDao();
        veicoloDao = db.veicoloDao();

        selectedVeicoloId = savedInstanceState != null
                ? savedInstanceState.getString(STATE_VEICOLO_ID)
                : getIntent().getStringExtra(EXTRA_VEICOLO_ID);

        emptyView = findViewById(R.id.emptyView);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ManutenzioneAdapter();
        adapter.setOnManutenzioneActionListener(new ManutenzioneAdapter.OnManutenzioneActionListener() {
            @Override
            public void onEdit(Manutenzione manutenzione) {
                showManutenzioneDialog(manutenzione);
            }

            @Override
            public void onDelete(Manutenzione manutenzione) {
                confermaElimina(manutenzione);
            }
        });
        recyclerView.setAdapter(adapter);

        fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showManutenzioneDialog(null));

        spinnerVeicolo = findViewById(R.id.spinnerVeicolo);
        spinnerVeicolo.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (veicoli == null || position >= veicoli.size()) return;
                String scelto = veicoli.get(position).getId();
                // Scatta anche per la selezione impostata da loadVeicoli:
                // in quel caso l'elenco e' gia' stato caricato
                if (!scelto.equals(selectedVeicoloId)) {
                    selectedVeicoloId = scelto;
                    loadData();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Il listener dei dati MQTT e' unico: lo si prende ogni volta che si
        // torna in primo piano e lo si rilascia in onPause, come fa MainActivity
        MqttSyncManager.getInstance(this).setOnSyncDataReceivedListener(this::loadVeicoli);
        loadVeicoli();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MqttSyncManager.getInstance(this).setOnSyncDataReceivedListener(null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_VEICOLO_ID, selectedVeicoloId);
    }

    /**
     * Ricarica le auto e poi gli interventi: un'auto puo' essere stata
     * eliminata, anche da un altro telefono, mentre questa schermata era aperta.
     */
    private void loadVeicoli() {
        executor.execute(() -> {
            List<Veicolo> list = veicoloDao.getAll();
            runOnUiThread(() -> {
                veicoli = list;
                if (veicoli.isEmpty()) {
                    spinnerVeicolo.setAdapter(null);
                    selectedVeicoloId = null;
                    fab.setEnabled(false);
                    adapter.setData(new ArrayList<>());
                    emptyView.setText(R.string.aggiungi_veicolo_prima);
                    emptyView.setVisibility(View.VISIBLE);
                    return;
                }

                fab.setEnabled(true);
                ArrayAdapter<Veicolo> spinnerAdapter = new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, veicoli);
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerVeicolo.setAdapter(spinnerAdapter);

                int selectionIndex = 0;
                for (int i = 0; i < veicoli.size(); i++) {
                    if (veicoli.get(i).getId().equals(selectedVeicoloId)) {
                        selectionIndex = i;
                        break;
                    }
                }
                selectedVeicoloId = veicoli.get(selectionIndex).getId();
                spinnerVeicolo.setSelection(selectionIndex);
                loadData();
            });
        });
    }

    private void loadData() {
        if (selectedVeicoloId == null) return;
        final String veicoloId = selectedVeicoloId;
        executor.execute(() -> {
            List<Manutenzione> list = manutenzioneDao.getByVeicolo(veicoloId);
            runOnUiThread(() -> {
                // Risposta di una richiesta superata da un cambio di auto
                if (!veicoloId.equals(selectedVeicoloId)) return;
                adapter.setData(list);
                if (list.isEmpty()) {
                    emptyView.setText(R.string.nessuna_manutenzione);
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    emptyView.setVisibility(View.GONE);
                }
            });
        });
    }

    /** @param existing l'intervento da modificare, oppure null per uno nuovo */
    private void showManutenzioneDialog(Manutenzione existing) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_manutenzione, null);

        TextInputEditText editData = dialogView.findViewById(R.id.editData);
        TextInputLayout layoutTipo = dialogView.findViewById(R.id.layoutTipo);
        AutoCompleteTextView editTipo = dialogView.findViewById(R.id.editTipo);
        TextInputLayout layoutDescrizione = dialogView.findViewById(R.id.layoutDescrizione);
        TextInputEditText editDescrizione = dialogView.findViewById(R.id.editDescrizione);
        TextInputLayout layoutKm = dialogView.findViewById(R.id.layoutKm);
        TextInputEditText editKm = dialogView.findViewById(R.id.editKm);
        TextInputLayout layoutCosto = dialogView.findViewById(R.id.layoutCosto);
        TextInputEditText editCosto = dialogView.findViewById(R.id.editCosto);

        // Valori modificati dai listener: array per poterli scrivere dalle lambda
        final String[] data = {existing != null ? existing.getData() : Manutenzione.oggiIso()};
        // Il codice resta quello salvato finche' l'utente non sceglie un altro
        // tipo: un codice sconosciuto (versione piu' recente) non va riscritto
        final String[] codiceTipo = {existing != null ? existing.getTipo() : null};

        editData.setText(Manutenzione.dataVisualizzata(data[0]));
        editData.setOnClickListener(v -> {
            int[] ymd = Manutenzione.componenti(data[0]);
            new DatePickerDialog(this,
                    (view, year, month, dayOfMonth) -> {
                        data[0] = Manutenzione.dataIso(year, month, dayOfMonth);
                        editData.setText(Manutenzione.dataVisualizzata(data[0]));
                    },
                    ymd[0], ymd[1], ymd[2]
            ).show();
        });

        final TipoManutenzione[] tipi = TipoManutenzione.values();
        String[] etichette = new String[tipi.length];
        for (int i = 0; i < tipi.length; i++) {
            etichette[i] = getString(tipi[i].getEtichetta());
        }
        editTipo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, etichette));
        editTipo.setOnItemClickListener((parent, view, position, id) -> {
            codiceTipo[0] = tipi[position].name();
            layoutTipo.setError(null);
        });

        if (existing != null) {
            // false: senza filtro, altrimenti il menu mostrerebbe solo questa voce
            editTipo.setText(getString(TipoManutenzione.fromCodice(existing.getTipo()).getEtichetta()), false);
            editDescrizione.setText(existing.getDescrizione());
            if (existing.getKm() != null) {
                editKm.setText(String.valueOf(existing.getKm()));
            }
            editCosto.setText(String.format(Locale.US, "%.2f", existing.getCosto()));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing != null ? R.string.modifica_manutenzione : R.string.nuova_manutenzione)
                .setView(dialogView)
                .setPositiveButton(R.string.salva, null)
                .setNegativeButton(R.string.annulla, null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            layoutTipo.setError(null);
            layoutDescrizione.setError(null);
            layoutKm.setError(null);
            layoutCosto.setError(null);

            boolean valid = true;

            String descrizione = getText(editDescrizione);
            String kmStr = getText(editKm);
            String costoStr = getText(editCosto).replace(',', '.');

            if (codiceTipo[0] == null) {
                layoutTipo.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            } else if (TipoManutenzione.fromCodice(codiceTipo[0]) == TipoManutenzione.ALTRO
                    && descrizione.isEmpty()) {
                layoutDescrizione.setError(getString(R.string.descrizione_obbligatoria_altro));
                valid = false;
            }

            Integer km = null;
            if (!kmStr.isEmpty()) {
                try {
                    km = Integer.parseInt(kmStr);
                    if (km < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    layoutKm.setError(getString(R.string.valore_non_valido));
                    valid = false;
                }
            }

            double costo = 0;
            if (costoStr.isEmpty()) {
                layoutCosto.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            } else {
                try {
                    // Zero ammesso: interventi in garanzia o gratuiti
                    costo = Double.parseDouble(costoStr);
                    if (costo < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    layoutCosto.setError(getString(R.string.valore_non_valido));
                    valid = false;
                }
            }

            if (!valid) return;

            Manutenzione m = existing != null ? existing : new Manutenzione();
            m.setData(data[0]);
            m.setTipo(codiceTipo[0]);
            m.setDescrizione(descrizione.isEmpty() ? null : descrizione);
            m.setKm(km);
            m.setCosto(costo);
            if (existing != null) {
                m.setUpdatedAt(System.currentTimeMillis());
            } else {
                m.setVeicoloId(selectedVeicoloId);
            }

            executor.execute(() -> {
                if (existing != null) {
                    manutenzioneDao.update(m);
                } else {
                    manutenzioneDao.insert(m);
                }
                MqttSyncManager.getInstance(ManutenzioneActivity.this).publishManutenzione(m);
                runOnUiThread(this::loadData);
            });

            dialog.dismiss();
        });
    }

    private void confermaElimina(Manutenzione manutenzione) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.elimina)
                .setMessage(R.string.conferma_elimina_manutenzione)
                .setPositiveButton(R.string.elimina, (d, which) -> {
                    executor.execute(() -> {
                        manutenzioneDao.delete(manutenzione);
                        MqttSyncManager.getInstance(ManutenzioneActivity.this)
                                .publishDeleteManutenzione(manutenzione.getId());
                        runOnUiThread(this::loadData);
                    });
                })
                .setNegativeButton(R.string.annulla, null)
                .show();
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

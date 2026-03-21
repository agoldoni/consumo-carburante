package it.agoldoni.consumocarburanti;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private RifornimentoDao rifornimentoDao;
    private VeicoloDao veicoloDao;
    private RifornimentoAdapter adapter;
    private TextView emptyView;
    private FloatingActionButton fab;
    private Spinner spinnerVeicolo;
    private DrawerLayout drawerLayout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);

    private String selectedVeicoloId;
    private List<Veicolo> veicoli;
    private boolean isLoadingVeicoli;

    private static final String PREF_SELECTED_VEICOLO = "selectedVeicoloId";

    private FusedLocationProviderClient fusedLocationClient;
    private Location lastKnownLocation;
    private Runnable pendingAfterPermission;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean granted = result.containsValue(Boolean.TRUE);
                if (granted && pendingAfterPermission != null) {
                    pendingAfterPermission.run();
                }
                pendingAfterPermission = null;
            });

    private final ActivityResultLauncher<String[]> importFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    importData(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawerLayout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar, R.string.app_name, R.string.app_name);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.navigationView);
        navigationView.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_auto) {
                startActivity(new Intent(this, VeicoloActivity.class));
            } else if (item.getItemId() == R.id.nav_import) {
                importFileLauncher.launch(new String[]{"text/*"});
            } else if (item.getItemId() == R.id.nav_info) {
                showInfoDialog();
            }
            drawerLayout.closeDrawers();
            return true;
        });

        AppDatabase db = AppDatabase.getInstance(this);
        rifornimentoDao = db.rifornimentoDao();
        veicoloDao = db.veicoloDao();

        emptyView = findViewById(R.id.emptyView);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RifornimentoAdapter();
        adapter.setOnRifornimentoActionListener(new RifornimentoAdapter.OnRifornimentoActionListener() {
            @Override
            public void onEdit(Rifornimento rifornimento) {
                showEditDialog(rifornimento);
            }

            @Override
            public void onDelete(Rifornimento rifornimento) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.elimina)
                        .setMessage(R.string.conferma_elimina_rifornimento)
                        .setPositiveButton(R.string.elimina, (d, which) -> {
                            executor.execute(() -> {
                                rifornimentoDao.delete(rifornimento);
                                runOnUiThread(() -> loadData());
                            });
                        })
                        .setNegativeButton(R.string.annulla, null)
                        .show();
            }
        });
        recyclerView.setAdapter(adapter);

        fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showAddDialog());

        spinnerVeicolo = findViewById(R.id.spinnerVeicolo);
        spinnerVeicolo.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isLoadingVeicoli) return;
                if (veicoli != null && position < veicoli.size()) {
                    selectedVeicoloId = veicoli.get(position).getId();
                    getPreferences(MODE_PRIVATE).edit()
                            .putString(PREF_SELECTED_VEICOLO, selectedVeicoloId)
                            .apply();
                    loadData();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        loadVeicoli();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadVeicoli();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void fetchLocation(TextView textPosizione) {
        if (!hasLocationPermission()) {
            textPosizione.setText(R.string.permesso_posizione_negato);
            lastKnownLocation = null;
            return;
        }
        try {
            fusedLocationClient.getLastLocation().addOnSuccessListener(this, location -> {
                lastKnownLocation = location;
                if (location != null) {
                    textPosizione.setText(String.format(Locale.ITALY,
                            "%s (%.5f, %.5f)",
                            getString(R.string.posizione_acquisita),
                            location.getLatitude(), location.getLongitude()));
                } else {
                    textPosizione.setText(R.string.posizione_non_disponibile);
                }
            });
        } catch (SecurityException e) {
            textPosizione.setText(R.string.posizione_non_disponibile);
            lastKnownLocation = null;
        }
    }

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
                isLoadingVeicoli = true;
                ArrayAdapter<Veicolo> spinnerAdapter = new ArrayAdapter<>(
                        this, android.R.layout.simple_spinner_item, veicoli);
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinnerVeicolo.setAdapter(spinnerAdapter);

                String savedId = getPreferences(MODE_PRIVATE)
                        .getString(PREF_SELECTED_VEICOLO, null);
                int selectionIndex = 0;
                if (savedId != null) {
                    for (int i = 0; i < veicoli.size(); i++) {
                        if (veicoli.get(i).getId().equals(savedId)) {
                            selectionIndex = i;
                            break;
                        }
                    }
                }
                selectedVeicoloId = veicoli.get(selectionIndex).getId();
                spinnerVeicolo.setSelection(selectionIndex);
                isLoadingVeicoli = false;
                loadData();
            });
        });
    }

    private void loadData() {
        if (selectedVeicoloId == null) return;
        executor.execute(() -> {
            List<Rifornimento> list = rifornimentoDao.getByVeicolo(selectedVeicoloId);
            runOnUiThread(() -> {
                adapter.setData(list);
                if (list.isEmpty()) {
                    emptyView.setText(R.string.empty_list);
                    emptyView.setVisibility(View.VISIBLE);
                } else {
                    emptyView.setVisibility(View.GONE);
                }
            });
        });
    }

    private void showAddDialog() {
        lastKnownLocation = null;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_rifornimento, null);

        TextInputLayout layoutDataOra = dialogView.findViewById(R.id.layoutDataOra);
        TextInputEditText editDataOra = dialogView.findViewById(R.id.editDataOra);
        TextInputLayout layoutKm = dialogView.findViewById(R.id.layoutKm);
        TextInputEditText editKm = dialogView.findViewById(R.id.editKm);
        TextInputLayout layoutLitri = dialogView.findViewById(R.id.layoutLitri);
        TextInputEditText editLitri = dialogView.findViewById(R.id.editLitri);
        TextInputLayout layoutCosto = dialogView.findViewById(R.id.layoutCosto);
        TextInputEditText editCosto = dialogView.findViewById(R.id.editCosto);
        TextView textPosizione = dialogView.findViewById(R.id.textPosizione);

        Calendar selectedDateTime = Calendar.getInstance();
        editDataOra.setText(dateFormat.format(selectedDateTime.getTime()));

        // Acquisizione posizione
        if (hasLocationPermission()) {
            fetchLocation(textPosizione);
        } else {
            textPosizione.setText(R.string.posizione_non_disponibile);
            pendingAfterPermission = () -> fetchLocation(textPosizione);
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }

        editDataOra.setOnClickListener(v -> {
            new DatePickerDialog(this,
                    (view, year, month, dayOfMonth) -> {
                        selectedDateTime.set(Calendar.YEAR, year);
                        selectedDateTime.set(Calendar.MONTH, month);
                        selectedDateTime.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                        new TimePickerDialog(this,
                                (view2, hourOfDay, minute) -> {
                                    selectedDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                                    selectedDateTime.set(Calendar.MINUTE, minute);
                                    editDataOra.setText(dateFormat.format(selectedDateTime.getTime()));
                                },
                                selectedDateTime.get(Calendar.HOUR_OF_DAY),
                                selectedDateTime.get(Calendar.MINUTE),
                                true
                        ).show();
                    },
                    selectedDateTime.get(Calendar.YEAR),
                    selectedDateTime.get(Calendar.MONTH),
                    selectedDateTime.get(Calendar.DAY_OF_MONTH)
            ).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.nuovo_rifornimento)
                .setView(dialogView)
                .setPositiveButton(R.string.salva, null)
                .setNegativeButton(R.string.annulla, null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            layoutDataOra.setError(null);
            layoutKm.setError(null);
            layoutLitri.setError(null);
            layoutCosto.setError(null);

            boolean valid = true;

            String kmStr = getText(editKm);
            String litriStr = getText(editLitri).replace(',', '.');
            String costoStr = getText(editCosto).replace(',', '.');

            if (kmStr.isEmpty()) {
                layoutKm.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            }
            if (litriStr.isEmpty()) {
                layoutLitri.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            }
            if (costoStr.isEmpty()) {
                layoutCosto.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            }

            if (!valid) return;

            int km;
            double litri, costo;
            try {
                km = Integer.parseInt(kmStr);
                if (km <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                layoutKm.setError(getString(R.string.valore_non_valido));
                return;
            }
            try {
                litri = Double.parseDouble(litriStr);
                if (litri <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                layoutLitri.setError(getString(R.string.valore_non_valido));
                return;
            }
            try {
                costo = Double.parseDouble(costoStr);
                if (costo <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                layoutCosto.setError(getString(R.string.valore_non_valido));
                return;
            }

            Rifornimento r = new Rifornimento();
            r.setDatetime(selectedDateTime.getTimeInMillis());
            r.setKm(km);
            r.setQtaBenzina(litri);
            r.setCosto(costo);
            r.setVeicoloId(selectedVeicoloId);
            if (lastKnownLocation != null) {
                r.setLatitude(lastKnownLocation.getLatitude());
                r.setLongitude(lastKnownLocation.getLongitude());
            }

            executor.execute(() -> {
                rifornimentoDao.insert(r);
                runOnUiThread(this::loadData);
            });

            dialog.dismiss();
        });
    }

    private void showEditDialog(Rifornimento rifornimento) {
        lastKnownLocation = null;
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_rifornimento, null);

        TextInputLayout layoutDataOra = dialogView.findViewById(R.id.layoutDataOra);
        TextInputEditText editDataOra = dialogView.findViewById(R.id.editDataOra);
        TextInputLayout layoutKm = dialogView.findViewById(R.id.layoutKm);
        TextInputEditText editKm = dialogView.findViewById(R.id.editKm);
        TextInputLayout layoutLitri = dialogView.findViewById(R.id.layoutLitri);
        TextInputEditText editLitri = dialogView.findViewById(R.id.editLitri);
        TextInputLayout layoutCosto = dialogView.findViewById(R.id.layoutCosto);
        TextInputEditText editCosto = dialogView.findViewById(R.id.editCosto);
        TextView textPosizione = dialogView.findViewById(R.id.textPosizione);

        Calendar selectedDateTime = Calendar.getInstance();
        selectedDateTime.setTimeInMillis(rifornimento.getDatetime());
        editDataOra.setText(dateFormat.format(selectedDateTime.getTime()));
        editKm.setText(String.valueOf(rifornimento.getKm()));
        editLitri.setText(String.format(Locale.US, "%.2f", rifornimento.getQtaBenzina()));
        editCosto.setText(String.format(Locale.US, "%.2f", rifornimento.getCosto()));

        // Mostra posizione esistente o acquisisce nuova
        if (rifornimento.getLatitude() != null && rifornimento.getLongitude() != null) {
            textPosizione.setText(String.format(Locale.ITALY,
                    "%s (%.5f, %.5f)",
                    getString(R.string.posizione_acquisita),
                    rifornimento.getLatitude(), rifornimento.getLongitude()));
        } else if (hasLocationPermission()) {
            fetchLocation(textPosizione);
        } else {
            textPosizione.setText(R.string.posizione_non_disponibile);
        }

        editDataOra.setOnClickListener(v -> {
            new DatePickerDialog(this,
                    (view, year, month, dayOfMonth) -> {
                        selectedDateTime.set(Calendar.YEAR, year);
                        selectedDateTime.set(Calendar.MONTH, month);
                        selectedDateTime.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                        new TimePickerDialog(this,
                                (view2, hourOfDay, minute) -> {
                                    selectedDateTime.set(Calendar.HOUR_OF_DAY, hourOfDay);
                                    selectedDateTime.set(Calendar.MINUTE, minute);
                                    editDataOra.setText(dateFormat.format(selectedDateTime.getTime()));
                                },
                                selectedDateTime.get(Calendar.HOUR_OF_DAY),
                                selectedDateTime.get(Calendar.MINUTE),
                                true
                        ).show();
                    },
                    selectedDateTime.get(Calendar.YEAR),
                    selectedDateTime.get(Calendar.MONTH),
                    selectedDateTime.get(Calendar.DAY_OF_MONTH)
            ).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.modifica_rifornimento)
                .setView(dialogView)
                .setPositiveButton(R.string.salva, null)
                .setNegativeButton(R.string.annulla, null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            layoutDataOra.setError(null);
            layoutKm.setError(null);
            layoutLitri.setError(null);
            layoutCosto.setError(null);

            boolean valid = true;

            String kmStr = getText(editKm);
            String litriStr = getText(editLitri).replace(',', '.');
            String costoStr = getText(editCosto).replace(',', '.');

            if (kmStr.isEmpty()) {
                layoutKm.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            }
            if (litriStr.isEmpty()) {
                layoutLitri.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            }
            if (costoStr.isEmpty()) {
                layoutCosto.setError(getString(R.string.campo_obbligatorio));
                valid = false;
            }

            if (!valid) return;

            int km;
            double litri, costo;
            try {
                km = Integer.parseInt(kmStr);
                if (km <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                layoutKm.setError(getString(R.string.valore_non_valido));
                return;
            }
            try {
                litri = Double.parseDouble(litriStr);
                if (litri <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                layoutLitri.setError(getString(R.string.valore_non_valido));
                return;
            }
            try {
                costo = Double.parseDouble(costoStr);
                if (costo <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                layoutCosto.setError(getString(R.string.valore_non_valido));
                return;
            }

            rifornimento.setDatetime(selectedDateTime.getTimeInMillis());
            rifornimento.setKm(km);
            rifornimento.setQtaBenzina(litri);
            rifornimento.setCosto(costo);
            // Aggiorna posizione solo se acquisita nuova (non sovrascrive quella esistente)
            if (lastKnownLocation != null) {
                rifornimento.setLatitude(lastKnownLocation.getLatitude());
                rifornimento.setLongitude(lastKnownLocation.getLongitude());
            }

            executor.execute(() -> {
                rifornimentoDao.update(rifornimento);
                runOnUiThread(this::loadData);
            });

            dialog.dismiss();
        });
    }

    private void showInfoDialog() {
        String info = getString(R.string.info_app_title) + "\n\n"
                + "Versione: " + BuildConfig.VERSION_NAME
                + " (build " + BuildConfig.VERSION_CODE + ")\n"
                + "Autore: " + BuildConfig.APP_AUTHOR + "\n"
                + "Data build: " + BuildConfig.BUILD_DATE + "\n"
                + "Package: " + BuildConfig.APPLICATION_ID;

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_info)
                .setMessage(info)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_share) {
            shareData();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void shareData() {
        if (selectedVeicoloId == null || veicoli == null || veicoli.isEmpty()) {
            Toast.makeText(this, R.string.nessun_rifornimento_da_esportare, Toast.LENGTH_SHORT).show();
            return;
        }

        String veicoloNome = "";
        for (Veicolo v : veicoli) {
            if (v.getId().equals(selectedVeicoloId)) {
                veicoloNome = v.getNome() != null ? v.getNome() : "veicolo";
                break;
            }
        }

        final String nomeVeicolo = veicoloNome.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        final String nomeVeicoloOriginal = veicoloNome;

        executor.execute(() -> {
            List<Rifornimento> list = rifornimentoDao.getByVeicolo(selectedVeicoloId);

            if (list.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.nessun_rifornimento_da_esportare, Toast.LENGTH_SHORT).show());
                return;
            }

            String exportDate = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date());
            String fileName = "rifornimenti_" + nomeVeicolo + "_" + exportDate + ".csv";
            File csvFile = new File(getCacheDir(), fileName);

            try (FileWriter writer = new FileWriter(csvFile)) {
                writer.write("Id,Veicolo,Data,Km,Litri,Costo,Latitudine,Longitudine\n");

                for (Rifornimento r : list) {
                    String data = dateFormat.format(new Date(r.getDatetime()));
                    String lat = r.getLatitude() != null
                            ? String.format(Locale.US, "%.5f", r.getLatitude()) : "";
                    String lon = r.getLongitude() != null
                            ? String.format(Locale.US, "%.5f", r.getLongitude()) : "";

                    writer.write(String.format(Locale.US, "%s,%s,%s,%d,%.2f,%.2f,%s,%s\n",
                            r.getId(), nomeVeicoloOriginal, data, r.getKm(), r.getQtaBenzina(), r.getCosto(), lat, lon));
                }

                runOnUiThread(() -> {
                    Uri uri = FileProvider.getUriForFile(this,
                            getApplicationContext().getPackageName() + ".fileprovider",
                            csvFile);

                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/csv");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent,
                            getString(R.string.condividi_rifornimenti)));
                });

            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.errore_esportazione, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void importData(Uri uri) {
        executor.execute(() -> {
            int importati = 0;
            int duplicati = 0;
            int errori = 0;
            Map<String, String> veicoloCache = new HashMap<>();

            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

                String header = reader.readLine();
                if (header == null || !header.startsWith("Id,Veicolo,Data,")) {
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.file_non_valido, Toast.LENGTH_LONG).show());
                    return;
                }

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    try {
                        String[] fields = line.split(",", -1);
                        if (fields.length < 6) {
                            errori++;
                            continue;
                        }

                        String id = fields[0].trim();
                        String nomeVeicolo = fields[1].trim();
                        String dataStr = fields[2].trim();
                        int km = Integer.parseInt(fields[3].trim());
                        double litri = Double.parseDouble(fields[4].trim());
                        double costo = Double.parseDouble(fields[5].trim());
                        Double lat = fields.length > 6 && !fields[6].trim().isEmpty()
                                ? Double.parseDouble(fields[6].trim()) : null;
                        Double lon = fields.length > 7 && !fields[7].trim().isEmpty()
                                ? Double.parseDouble(fields[7].trim()) : null;

                        // Deduplicazione tramite UUID
                        if (rifornimentoDao.getById(id) != null) {
                            duplicati++;
                            continue;
                        }

                        // Cerca o crea veicolo
                        String veicoloId = veicoloCache.get(nomeVeicolo);
                        if (veicoloId == null) {
                            Veicolo veicolo = veicoloDao.getByNome(nomeVeicolo);
                            if (veicolo == null) {
                                veicolo = new Veicolo();
                                veicolo.setNome(nomeVeicolo);
                                veicoloDao.insert(veicolo);
                            }
                            veicoloId = veicolo.getId();
                            veicoloCache.put(nomeVeicolo, veicoloId);
                        }

                        // Parsing data
                        Date data = dateFormat.parse(dataStr);
                        if (data == null) {
                            errori++;
                            continue;
                        }

                        Rifornimento r = new Rifornimento();
                        r.setId(id);
                        r.setDatetime(data.getTime());
                        r.setKm(km);
                        r.setQtaBenzina(litri);
                        r.setCosto(costo);
                        r.setVeicoloId(veicoloId);
                        r.setLatitude(lat);
                        r.setLongitude(lon);

                        rifornimentoDao.insert(r);
                        importati++;

                    } catch (NumberFormatException | ParseException e) {
                        errori++;
                    }
                }

            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        R.string.errore_importazione, Toast.LENGTH_LONG).show());
                return;
            }

            final int finalImportati = importati;
            final int finalDuplicati = duplicati;
            final int finalErrori = errori;
            runOnUiThread(() -> {
                Toast.makeText(this,
                        getString(R.string.importazione_completata,
                                finalImportati, finalDuplicati, finalErrori),
                        Toast.LENGTH_LONG).show();
                loadVeicoli();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}

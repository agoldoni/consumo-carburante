package it.agoldoni.consumocarburanti;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VeicoloActivity extends AppCompatActivity implements VeicoloAdapter.OnVeicoloActionListener {

    private VeicoloDao dao;
    private VeicoloAdapter adapter;
    private TextView emptyView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_veicolo);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        dao = AppDatabase.getInstance(this).veicoloDao();

        emptyView = findViewById(R.id.emptyView);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VeicoloAdapter(this);
        recyclerView.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showVeicoloDialog(null));

        loadData();
    }

    private void loadData() {
        executor.execute(() -> {
            List<Veicolo> list = dao.getAll();
            runOnUiThread(() -> {
                adapter.setData(list);
                emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showVeicoloDialog(Veicolo existing) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_veicolo, null);

        TextInputLayout layoutNome = dialogView.findViewById(R.id.layoutNome);
        TextInputEditText editNome = dialogView.findViewById(R.id.editNome);
        TextInputLayout layoutTarga = dialogView.findViewById(R.id.layoutTarga);
        TextInputEditText editTarga = dialogView.findViewById(R.id.editTarga);

        if (existing != null) {
            editNome.setText(existing.getNome());
            editTarga.setText(existing.getTarga());
        }

        String title = existing != null
                ? getString(R.string.modifica_veicolo)
                : getString(R.string.nuovo_veicolo);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(dialogView)
                .setPositiveButton(R.string.salva, null)
                .setNegativeButton(R.string.annulla, null)
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            layoutNome.setError(null);

            String nome = getText(editNome);
            String targa = getText(editTarga);

            if (nome.isEmpty()) {
                layoutNome.setError(getString(R.string.campo_obbligatorio));
                return;
            }

            if (existing != null) {
                existing.setNome(nome);
                existing.setTarga(targa);
                executor.execute(() -> {
                    dao.update(existing);
                    runOnUiThread(this::loadData);
                });
            } else {
                Veicolo veicolo = new Veicolo();
                veicolo.setNome(nome);
                veicolo.setTarga(targa);
                executor.execute(() -> {
                    dao.insert(veicolo);
                    runOnUiThread(this::loadData);
                });
            }

            dialog.dismiss();
        });
    }

    @Override
    public void onEdit(Veicolo veicolo) {
        showVeicoloDialog(veicolo);
    }

    @Override
    public void onDelete(Veicolo veicolo) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.elimina)
                .setMessage(R.string.conferma_elimina_veicolo)
                .setPositiveButton(R.string.elimina, (d, which) -> {
                    executor.execute(() -> {
                        dao.delete(veicolo);
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

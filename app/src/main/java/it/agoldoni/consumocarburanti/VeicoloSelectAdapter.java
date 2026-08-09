package it.agoldoni.consumocarburanti;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Elenco delle auto con casella di selezione, per scegliere quali proporre in
 * una sessione Bluetooth.
 */
public class VeicoloSelectAdapter extends RecyclerView.Adapter<VeicoloSelectAdapter.ViewHolder> {

    public interface OnSelezioneCambiataListener {
        void onSelezioneCambiata(int quanteSelezionate);
    }

    private final List<Veicolo> veicoli = new ArrayList<>();
    private final Map<String, Integer> conteggi = new HashMap<>();
    private final Set<String> selezionati = new LinkedHashSet<>();
    private OnSelezioneCambiataListener listener;

    public void setOnSelezioneCambiataListener(OnSelezioneCambiataListener listener) {
        this.listener = listener;
    }

    public void setData(List<Veicolo> nuoviVeicoli, Map<String, Integer> nuoviConteggi) {
        veicoli.clear();
        veicoli.addAll(nuoviVeicoli);
        conteggi.clear();
        conteggi.putAll(nuoviConteggi);
        selezionati.clear();
        notifyDataSetChanged();
        notificaSelezione();
    }

    public List<String> getSelezionati() {
        return new ArrayList<>(selezionati);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_veicolo_select, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Veicolo veicolo = veicoli.get(position);
        holder.bind(veicolo);
    }

    @Override
    public int getItemCount() {
        return veicoli.size();
    }

    private void notificaSelezione() {
        if (listener != null) {
            listener.onSelezioneCambiata(selezionati.size());
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCheckBox check;
        private final TextView textNome;
        private final TextView textDettagli;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.checkSelezionato);
            textNome = itemView.findViewById(R.id.textNome);
            textDettagli = itemView.findViewById(R.id.textDettagli);
            itemView.setOnClickListener(v -> {
                int posizione = getBindingAdapterPosition();
                if (posizione == RecyclerView.NO_POSITION) return;
                String id = veicoli.get(posizione).getId();
                if (!selezionati.remove(id)) {
                    selezionati.add(id);
                }
                notifyItemChanged(posizione);
                notificaSelezione();
            });
        }

        void bind(Veicolo veicolo) {
            check.setChecked(selezionati.contains(veicolo.getId()));
            textNome.setText(veicolo.getNome());

            String targa = veicolo.getTarga();
            Integer conteggio = conteggi.get(veicolo.getId());
            String rifornimenti = itemView.getContext().getResources().getQuantityString(
                    R.plurals.bt_n_rifornimenti,
                    conteggio != null ? conteggio : 0,
                    conteggio != null ? conteggio : 0);
            textDettagli.setText(targa != null && !targa.isEmpty()
                    ? targa + " • " + rifornimenti
                    : rifornimenti);
        }
    }
}

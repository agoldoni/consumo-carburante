package it.agoldoni.consumocarburanti;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Le auto proposte dall'altro dispositivo, ognuna con la casella di
 * accettazione e la scelta di dove finiranno i dati: una nuova auto oppure una
 * gia' presente in locale, che in quel caso ne adottera' l'identificativo.
 *
 * <p>La destinazione viene preselezionata confrontando la targa (normalizzata:
 * maiuscole, senza separatori) e, in seconda battuta, il nome.
 */
public class BtOfferAdapter extends RecyclerView.Adapter<BtOfferAdapter.ViewHolder> {

    /** Prima voce dello spinner: crea una nuova auto invece di collegarne una. */
    private static final int DESTINAZIONE_NUOVA = 0;

    private static class Riga {
        final BtMessages.VeicoloOfferto offerto;
        /** True quando l'id remoto esiste gia' in locale: nulla da collegare. */
        final boolean giaCondivisa;
        boolean accettata = true;
        int destinazione = DESTINAZIONE_NUOVA;

        Riga(BtMessages.VeicoloOfferto offerto, boolean giaCondivisa) {
            this.offerto = offerto;
            this.giaCondivisa = giaCondivisa;
        }
    }

    private final SimpleDateFormat formatoData = new SimpleDateFormat("dd/MM/yyyy", Locale.ITALY);
    private final List<Riga> righe = new ArrayList<>();
    private final List<Veicolo> veicoliLocali = new ArrayList<>();
    private ArrayAdapter<String> destinazioni;

    public void setData(Context context, List<BtMessages.VeicoloOfferto> offerta,
                        List<Veicolo> locali) {
        veicoliLocali.clear();
        veicoliLocali.addAll(locali);

        List<String> etichette = new ArrayList<>();
        etichette.add(context.getString(R.string.bt_crea_nuova));
        for (Veicolo v : veicoliLocali) {
            etichette.add(context.getString(R.string.bt_collega_a, v.getNome()));
        }
        destinazioni = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, etichette);
        destinazioni.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        righe.clear();
        for (BtMessages.VeicoloOfferto o : offerta) {
            Riga riga = new Riga(o, indiceLocale(o.id) >= 0);
            if (!riga.giaCondivisa) {
                riga.destinazione = suggerisciDestinazione(o);
            }
            righe.add(riga);
        }
        notifyDataSetChanged();
    }

    /** Le auto accettate, pronte per il messaggio di risposta. */
    public List<BtMessages.AutoAccettata> getScelte() {
        List<BtMessages.AutoAccettata> scelte = new ArrayList<>();
        for (Riga riga : righe) {
            if (!riga.accettata) continue;
            String localId = null;
            if (!riga.giaCondivisa && riga.destinazione != DESTINAZIONE_NUOVA) {
                localId = veicoliLocali.get(riga.destinazione - 1).getId();
            }
            scelte.add(new BtMessages.AutoAccettata(riga.offerto.id, localId));
        }
        return scelte;
    }

    private int indiceLocale(String id) {
        for (int i = 0; i < veicoliLocali.size(); i++) {
            if (veicoliLocali.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    /**
     * Stessa targa, o in mancanza stesso nome: nella pratica e' la stessa auto
     * registrata due volte, quindi si propone il collegamento.
     */
    private int suggerisciDestinazione(BtMessages.VeicoloOfferto offerto) {
        String targaRemota = normalizzaTarga(offerto.targa);
        if (!targaRemota.isEmpty()) {
            for (int i = 0; i < veicoliLocali.size(); i++) {
                if (targaRemota.equals(normalizzaTarga(veicoliLocali.get(i).getTarga()))) {
                    return i + 1;
                }
            }
        }
        if (offerto.nome != null && !offerto.nome.trim().isEmpty()) {
            for (int i = 0; i < veicoliLocali.size(); i++) {
                String nomeLocale = veicoliLocali.get(i).getNome();
                if (nomeLocale != null && nomeLocale.trim().equalsIgnoreCase(offerto.nome.trim())) {
                    return i + 1;
                }
            }
        }
        return DESTINAZIONE_NUOVA;
    }

    private static String normalizzaTarga(String targa) {
        return targa == null ? "" : targa.toUpperCase(Locale.ITALY).replaceAll("[^A-Z0-9]", "");
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bt_offer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(righe.get(position));
    }

    @Override
    public int getItemCount() {
        return righe.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        private final MaterialCheckBox check;
        private final TextView textNome;
        private final TextView textDettagli;
        private final TextView textDestinazioneEtichetta;
        private final TextView textGiaCondivisa;
        private final Spinner spinner;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            check = itemView.findViewById(R.id.checkAccettata);
            textNome = itemView.findViewById(R.id.textNome);
            textDettagli = itemView.findViewById(R.id.textDettagli);
            textDestinazioneEtichetta = itemView.findViewById(R.id.textDestinazioneEtichetta);
            textGiaCondivisa = itemView.findViewById(R.id.textGiaCondivisa);
            spinner = itemView.findViewById(R.id.spinnerDestinazione);
        }

        void bind(Riga riga) {
            textNome.setText(riga.offerto.nome);
            textDettagli.setText(descrivi(riga.offerto));

            // I listener vanno staccati prima di reimpostare i valori,
            // altrimenti il riciclo delle view li fa scattare sulla riga sbagliata
            check.setOnCheckedChangeListener(null);
            check.setChecked(riga.accettata);
            check.setOnCheckedChangeListener((buttonView, isChecked) -> {
                int posizione = getBindingAdapterPosition();
                if (posizione != RecyclerView.NO_POSITION) {
                    righe.get(posizione).accettata = isChecked;
                }
            });

            boolean mostraSpinner = !riga.giaCondivisa && !veicoliLocali.isEmpty();
            textGiaCondivisa.setVisibility(riga.giaCondivisa ? View.VISIBLE : View.GONE);
            textDestinazioneEtichetta.setVisibility(mostraSpinner ? View.VISIBLE : View.GONE);
            spinner.setVisibility(mostraSpinner ? View.VISIBLE : View.GONE);

            if (mostraSpinner) {
                spinner.setOnItemSelectedListener(null);
                spinner.setAdapter(destinazioni);
                spinner.setSelection(riga.destinazione, false);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view,
                                               int posizioneVoce, long id) {
                        int posizione = getBindingAdapterPosition();
                        if (posizione != RecyclerView.NO_POSITION) {
                            righe.get(posizione).destinazione = posizioneVoce;
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            }
        }

        private String descrivi(BtMessages.VeicoloOfferto offerto) {
            Context context = itemView.getContext();
            StringBuilder sb = new StringBuilder();
            if (offerto.targa != null && !offerto.targa.isEmpty()) {
                sb.append(offerto.targa).append(" • ");
            }
            sb.append(context.getResources().getQuantityString(
                    R.plurals.bt_n_rifornimenti, offerto.nRifornimenti, offerto.nRifornimenti));
            if (offerto.nRifornimenti > 0 && offerto.primo > 0) {
                sb.append(" • ").append(context.getString(R.string.bt_periodo,
                        formatoData.format(new Date(offerto.primo)),
                        formatoData.format(new Date(offerto.ultimo))));
            }
            return sb.toString();
        }
    }
}

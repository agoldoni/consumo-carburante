package it.agoldoni.consumocarburanti;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ManutenzioneAdapter extends RecyclerView.Adapter<ManutenzioneAdapter.ViewHolder> {

    public interface OnManutenzioneActionListener {
        void onEdit(Manutenzione manutenzione);
        void onDelete(Manutenzione manutenzione);
    }

    private List<Manutenzione> items = new ArrayList<>();
    private OnManutenzioneActionListener listener;

    public void setOnManutenzioneActionListener(OnManutenzioneActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<Manutenzione> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_manutenzione, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Manutenzione item = items.get(position);
        Context context = holder.itemView.getContext();

        holder.textData.setText(Manutenzione.dataVisualizzata(item.getData()));
        holder.textCosto.setText(String.format(Locale.ITALY, "€ %.2f", item.getCosto()));
        holder.textTipo.setText(TipoManutenzione.fromCodice(item.getTipo()).getEtichetta());

        if (item.getKm() != null) {
            holder.textKm.setText(String.format(Locale.ITALY,
                    context.getString(R.string.manutenzione_km), item.getKm()));
            holder.textKm.setVisibility(View.VISIBLE);
        } else {
            holder.textKm.setVisibility(View.GONE);
        }

        String descrizione = item.getDescrizione();
        if (descrizione != null && !descrizione.isEmpty()) {
            holder.textDescrizione.setText(descrizione);
            holder.textDescrizione.setVisibility(View.VISIBLE);
        } else {
            holder.textDescrizione.setVisibility(View.GONE);
        }

        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) listener.onEdit(item);
        });
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textData;
        final TextView textCosto;
        final TextView textTipo;
        final TextView textKm;
        final TextView textDescrizione;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textData = itemView.findViewById(R.id.textData);
            textCosto = itemView.findViewById(R.id.textCosto);
            textTipo = itemView.findViewById(R.id.textTipo);
            textKm = itemView.findViewById(R.id.textKm);
            textDescrizione = itemView.findViewById(R.id.textDescrizione);
            btnEdit = itemView.findViewById(R.id.btnEditManutenzione);
            btnDelete = itemView.findViewById(R.id.btnDeleteManutenzione);
        }
    }
}

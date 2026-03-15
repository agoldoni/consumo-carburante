package it.agoldoni.consumocarburanti;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RifornimentoAdapter extends RecyclerView.Adapter<RifornimentoAdapter.ViewHolder> {

    public interface OnRifornimentoActionListener {
        void onEdit(Rifornimento rifornimento);
        void onDelete(Rifornimento rifornimento);
    }

    private List<Rifornimento> items = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);
    private OnRifornimentoActionListener listener;

    public void setOnRifornimentoActionListener(OnRifornimentoActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<Rifornimento> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_rifornimento, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Rifornimento item = items.get(position);

        holder.textDatetime.setText(dateFormat.format(new Date(item.getDatetime())));
        holder.textCosto.setText(String.format(Locale.ITALY, "€ %.2f", item.getCosto()));
        holder.textKm.setText(String.format(Locale.ITALY, "Km: %,d", item.getKm()));
        holder.textLitri.setText(String.format(Locale.ITALY, "Litri: %.1f", item.getQtaBenzina()));

        // Icona mappa
        if (item.getLatitude() != null && item.getLongitude() != null) {
            holder.btnMap.setVisibility(View.VISIBLE);
            holder.btnMap.setOnClickListener(v -> {
                Uri geoUri = Uri.parse(String.format(Locale.US,
                        "geo:%f,%f?q=%f,%f(Rifornimento)",
                        item.getLatitude(), item.getLongitude(),
                        item.getLatitude(), item.getLongitude()));
                Intent mapIntent = new Intent(Intent.ACTION_VIEW, geoUri);
                v.getContext().startActivity(mapIntent);
            });
        } else {
            holder.btnMap.setVisibility(View.GONE);
            holder.btnMap.setOnClickListener(null);
        }

        // Calcolo km/l: la lista è DESC, quindi position+1 è il rifornimento precedente
        if (position < items.size() - 1) {
            Rifornimento precedente = items.get(position + 1);
            int kmPercorsi = item.getKm() - precedente.getKm();
            if (kmPercorsi > 0 && item.getQtaBenzina() > 0) {
                double kmPerLitro = (double) kmPercorsi / item.getQtaBenzina();
                holder.textConsumo.setText(String.format(Locale.ITALY, "Consumo: %.1f km/l", kmPerLitro));
                holder.textConsumo.setVisibility(View.VISIBLE);
            } else {
                holder.textConsumo.setVisibility(View.GONE);
            }
        } else {
            // Prima voce (la più vecchia): nessun dato precedente
            holder.textConsumo.setVisibility(View.GONE);
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
        final TextView textDatetime;
        final TextView textCosto;
        final TextView textKm;
        final TextView textLitri;
        final TextView textConsumo;
        final ImageButton btnMap;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textDatetime = itemView.findViewById(R.id.textDatetime);
            textCosto = itemView.findViewById(R.id.textCosto);
            textKm = itemView.findViewById(R.id.textKm);
            textLitri = itemView.findViewById(R.id.textLitri);
            textConsumo = itemView.findViewById(R.id.textConsumo);
            btnMap = itemView.findViewById(R.id.btnMap);
            btnEdit = itemView.findViewById(R.id.btnEditRifornimento);
            btnDelete = itemView.findViewById(R.id.btnDeleteRifornimento);
        }
    }
}

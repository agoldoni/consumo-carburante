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
    private boolean useL100km = false;

    public void setOnRifornimentoActionListener(OnRifornimentoActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<Rifornimento> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    public void setUseL100km(boolean useL100km) {
        if (this.useL100km != useL100km) {
            this.useL100km = useL100km;
            notifyDataSetChanged();
        }
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
        holder.textLitri.setText(String.format(Locale.ITALY, "Litri: %.2f", item.getQtaBenzina()));

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

        // Calcolo km/l: mostrato sulla voce precedente (più vecchia) perché i km
        // sono stati percorsi con il carburante di quel rifornimento.
        // La lista è DESC, quindi position-1 è il rifornimento successivo (più recente).
        if (position > 0) {
            Rifornimento successivo = items.get(position - 1);
            int kmPercorsi = successivo.getKm() - item.getKm();
            if (kmPercorsi > 0) {
                holder.textKmParziali.setText(String.format(Locale.ITALY, "%,d", kmPercorsi));
                holder.textKmParziali.setVisibility(View.VISIBLE);
            } else {
                holder.textKmParziali.setVisibility(View.GONE);
            }
            if (kmPercorsi > 0 && successivo.getQtaBenzina() > 0) {
                double kmPerLitro = (double) kmPercorsi / successivo.getQtaBenzina();
                if (useL100km) {
                    double l100km = 100.0 / kmPerLitro;
                    holder.textConsumo.setText(holder.itemView.getContext()
                            .getString(R.string.consumo_l_per_100km, l100km));
                } else {
                    holder.textConsumo.setText(holder.itemView.getContext()
                            .getString(R.string.consumo_km_per_l, kmPerLitro));
                }
                holder.textConsumo.setVisibility(View.VISIBLE);

                // Costo per 100 km: (100 / km_per_litro) × prezzo_al_litro
                double prezzoAlLitro = successivo.getCosto() / successivo.getQtaBenzina();
                double costoPer100km = (100.0 / kmPerLitro) * prezzoAlLitro;
                holder.textCostoKm.setText(String.format(Locale.ITALY, "Costo: %.2f €/100km", costoPer100km));
                holder.textCostoKm.setVisibility(View.VISIBLE);
            } else {
                holder.textConsumo.setVisibility(View.GONE);
                holder.textCostoKm.setVisibility(View.GONE);
            }
        } else {
            // Ultimo rifornimento (il più recente): consumo non ancora calcolabile
            holder.textConsumo.setVisibility(View.GONE);
            holder.textCostoKm.setVisibility(View.GONE);
            holder.textKmParziali.setVisibility(View.GONE);
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
        final TextView textKmParziali;
        final TextView textLitri;
        final TextView textConsumo;
        final TextView textCostoKm;
        final ImageButton btnMap;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textDatetime = itemView.findViewById(R.id.textDatetime);
            textCosto = itemView.findViewById(R.id.textCosto);
            textKm = itemView.findViewById(R.id.textKm);
            textKmParziali = itemView.findViewById(R.id.textKmParziali);
            textLitri = itemView.findViewById(R.id.textLitri);
            textConsumo = itemView.findViewById(R.id.textConsumo);
            textCostoKm = itemView.findViewById(R.id.textCostoKm);
            btnMap = itemView.findViewById(R.id.btnMap);
            btnEdit = itemView.findViewById(R.id.btnEditRifornimento);
            btnDelete = itemView.findViewById(R.id.btnDeleteRifornimento);
        }
    }
}

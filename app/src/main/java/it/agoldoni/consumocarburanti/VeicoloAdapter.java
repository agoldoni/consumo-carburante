package it.agoldoni.consumocarburanti;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class VeicoloAdapter extends RecyclerView.Adapter<VeicoloAdapter.ViewHolder> {

    public interface OnVeicoloActionListener {
        void onEdit(Veicolo veicolo);
        void onDelete(Veicolo veicolo);
    }

    private List<Veicolo> items = new ArrayList<>();
    private final OnVeicoloActionListener listener;

    public VeicoloAdapter(OnVeicoloActionListener listener) {
        this.listener = listener;
    }

    public void setData(List<Veicolo> data) {
        this.items = data;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_veicolo, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Veicolo item = items.get(position);
        holder.textNome.setText(item.getNome());
        String targa = item.getTarga();
        if (targa != null && !targa.isEmpty()) {
            holder.textTarga.setText(targa);
            holder.textTarga.setVisibility(View.VISIBLE);
        } else {
            holder.textTarga.setVisibility(View.GONE);
        }
        holder.btnEdit.setOnClickListener(v -> listener.onEdit(item));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textNome;
        final TextView textTarga;
        final ImageButton btnEdit;
        final ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textNome = itemView.findViewById(R.id.textNome);
            textTarga = itemView.findViewById(R.id.textTarga);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}

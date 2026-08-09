package it.agoldoni.consumocarburanti;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Elenco dei dispositivi Bluetooth: prima quelli gia' accoppiati, poi quelli
 * trovati dalla ricerca.
 */
public class BluetoothDeviceAdapter
        extends RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder> {

    public interface OnDeviceSelezionatoListener {
        void onDeviceSelezionato(BluetoothDevice device);
    }

    private final List<BluetoothDevice> dispositivi = new ArrayList<>();
    private final OnDeviceSelezionatoListener listener;

    public BluetoothDeviceAdapter(OnDeviceSelezionatoListener listener) {
        this.listener = listener;
    }

    public void setData(List<BluetoothDevice> nuoviDispositivi) {
        dispositivi.clear();
        dispositivi.addAll(nuoviDispositivi);
        notifyDataSetChanged();
    }

    /** @return true se il dispositivo non era gia' in elenco */
    public boolean aggiungi(BluetoothDevice device) {
        for (BluetoothDevice presente : dispositivi) {
            if (presente.getAddress().equals(device.getAddress())) {
                return false;
            }
        }
        dispositivi.add(device);
        notifyItemInserted(dispositivi.size() - 1);
        return true;
    }

    public boolean isEmpty() {
        return dispositivi.isEmpty();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bt_device, parent, false);
        return new ViewHolder(view);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BluetoothDevice device = dispositivi.get(position);
        String nome;
        try {
            nome = device.getName();
        } catch (SecurityException e) {
            nome = null;
        }
        holder.textNome.setText(nome != null && !nome.isEmpty()
                ? nome
                : holder.itemView.getContext().getString(R.string.bt_dispositivo_senza_nome));
        holder.textIndirizzo.setText(device.getAddress());
    }

    @Override
    public int getItemCount() {
        return dispositivi.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        final TextView textNome;
        final TextView textIndirizzo;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textNome = itemView.findViewById(R.id.textNome);
            textIndirizzo = itemView.findViewById(R.id.textIndirizzo);
            itemView.setOnClickListener(v -> {
                int posizione = getBindingAdapterPosition();
                if (posizione != RecyclerView.NO_POSITION && listener != null) {
                    listener.onDeviceSelezionato(dispositivi.get(posizione));
                }
            });
        }
    }
}

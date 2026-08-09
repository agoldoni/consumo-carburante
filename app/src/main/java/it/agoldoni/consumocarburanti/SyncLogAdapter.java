package it.agoldoni.consumocarburanti;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SyncLogAdapter extends RecyclerView.Adapter<SyncLogAdapter.ViewHolder> {

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY);

    private List<SyncLog.Entry> items = new ArrayList<>();

    /** Gli eventi sono mostrati dal più recente. */
    public void setData(List<SyncLog.Entry> data) {
        items = new ArrayList<>(data);
        Collections.reverse(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_sync_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SyncLog.Entry item = items.get(position);
        holder.textTime.setText(timeFormat.format(new Date(item.timestamp)));
        holder.textCategory.setText(item.category);
        holder.textMessage.setText(item.message);

        int color = ContextCompat.getColor(holder.itemView.getContext(), colorFor(item.level));
        holder.levelBar.setBackgroundColor(color);
        holder.textCategory.setTextColor(color);
    }

    private static int colorFor(SyncLog.Level level) {
        switch (level) {
            case ERROR:
                return R.color.log_error;
            case WARN:
                return R.color.log_warn;
            default:
                return R.color.log_info;
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View levelBar;
        final TextView textTime;
        final TextView textCategory;
        final TextView textMessage;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            levelBar = itemView.findViewById(R.id.levelBar);
            textTime = itemView.findViewById(R.id.textTime);
            textCategory = itemView.findViewById(R.id.textCategory);
            textMessage = itemView.findViewById(R.id.textMessage);
        }
    }
}

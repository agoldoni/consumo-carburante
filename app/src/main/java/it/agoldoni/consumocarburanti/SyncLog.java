package it.agoldoni.consumocarburanti;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Diario degli eventi di sincronizzazione MQTT, mostrato dalla
 * {@link SyncStatusActivity}. Serve a capire a posteriori perché una sync non
 * è andata a buon fine: il logcat non è disponibile sul telefono dell'utente.
 *
 * Gli eventi restano in memoria (ultimi {@value #MAX_ENTRIES}) e su file, così
 * sopravvivono al riavvio dell'app.
 */
public class SyncLog {

    public enum Level {INFO, WARN, ERROR}

    /** Categorie usate come tag nella vista. */
    public static final String CAT_CONN = "CONN";
    public static final String CAT_SUB = "SUB";
    public static final String CAT_PUB = "PUB";
    public static final String CAT_RECV = "RECV";
    public static final String CAT_DB = "DB";
    public static final String CAT_BT = "BT";

    public static class Entry {
        public final long timestamp;
        public final Level level;
        public final String category;
        public final String message;

        Entry(long timestamp, Level level, String category, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.category = category;
            this.message = message;
        }
    }

    public static class Stats {
        public final int published;
        public final int received;
        public final int failed;
        public final int disconnections;
        public final long lastConnectedAt;
        public final long lastDisconnectedAt;
        public final long lastErrorAt;
        public final String lastError;

        Stats(int published, int received, int failed, int disconnections,
              long lastConnectedAt, long lastDisconnectedAt, long lastErrorAt, String lastError) {
            this.published = published;
            this.received = received;
            this.failed = failed;
            this.disconnections = disconnections;
            this.lastConnectedAt = lastConnectedAt;
            this.lastDisconnectedAt = lastDisconnectedAt;
            this.lastErrorAt = lastErrorAt;
            this.lastError = lastError;
        }
    }

    public interface OnLogChangedListener {
        void onLogChanged();
    }

    private static final int MAX_ENTRIES = 500;
    private static final long MAX_FILE_BYTES = 128 * 1024;
    private static final long NOTIFY_THROTTLE_MS = 250;
    private static final String LOG_FILE = "sync_log.txt";

    private static final String PREFS_NAME = "sync_diag";
    private static final String KEY_PUBLISHED = "published";
    private static final String KEY_RECEIVED = "received";
    private static final String KEY_FAILED = "failed";
    private static final String KEY_DISCONNECTIONS = "disconnections";
    private static final String KEY_LAST_CONNECTED = "last_connected_at";
    private static final String KEY_LAST_DISCONNECTED = "last_disconnected_at";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_ERROR_AT = "last_error_at";

    private static volatile SyncLog INSTANCE;

    private final File logFile;
    private final SharedPreferences prefs;
    private final Handler mainHandler;
    private final ExecutorService io;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<OnLogChangedListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean notifyScheduled = new AtomicBoolean(false);

    private int published;
    private int received;
    private int failed;
    private int disconnections;
    private long lastConnectedAt;
    private long lastDisconnectedAt;
    private long lastErrorAt;
    private String lastError;

    private SyncLog(Context context) {
        Context app = context.getApplicationContext();
        logFile = new File(app.getFilesDir(), LOG_FILE);
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
        io = Executors.newSingleThreadExecutor();

        published = prefs.getInt(KEY_PUBLISHED, 0);
        received = prefs.getInt(KEY_RECEIVED, 0);
        failed = prefs.getInt(KEY_FAILED, 0);
        disconnections = prefs.getInt(KEY_DISCONNECTIONS, 0);
        lastConnectedAt = prefs.getLong(KEY_LAST_CONNECTED, 0);
        lastDisconnectedAt = prefs.getLong(KEY_LAST_DISCONNECTED, 0);
        lastErrorAt = prefs.getLong(KEY_LAST_ERROR_AT, 0);
        lastError = prefs.getString(KEY_LAST_ERROR, null);

        // Primo task della coda: gli append successivi vedranno già lo storico
        io.execute(this::loadFromFile);
    }

    public static SyncLog getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SyncLog.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SyncLog(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    // --- Scrittura ---

    public void info(String category, String message) {
        add(Level.INFO, category, message);
    }

    public void warn(String category, String message) {
        add(Level.WARN, category, message);
    }

    public void error(String category, String message) {
        recordError(message);
        add(Level.ERROR, category, message);
    }

    public void error(String category, String message, Throwable t) {
        error(category, message + ": " + describe(t));
    }

    /**
     * Descrizione leggibile di un errore: robusta a messaggi nulli e ripulita
     * dai nomi di classe qualificati che le librerie antepongono al messaggio
     * (es. "io.netty.channel.AbstractChannel$AnnotatedConnectException:
     * Connection refused" diventa "Connection refused").
     */
    public static String describe(Throwable t) {
        if (t == null) return "causa sconosciuta";
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) return t.getClass().getSimpleName();
        return msg.replaceAll("^(?:[a-zA-Z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?:\\$[\\w$]+)*:\\s*", "").trim();
    }

    private void add(Level level, String category, String message) {
        Entry entry = new Entry(System.currentTimeMillis(), level, category, message);
        io.execute(() -> {
            synchronized (entries) {
                entries.addLast(entry);
                while (entries.size() > MAX_ENTRIES) {
                    entries.removeFirst();
                }
            }
            appendToFile(entry);
            notifyChanged();
        });
    }

    // --- Contatori ---

    public void recordConnected() {
        lastConnectedAt = System.currentTimeMillis();
        prefs.edit().putLong(KEY_LAST_CONNECTED, lastConnectedAt).apply();
    }

    public void recordDisconnected() {
        lastDisconnectedAt = System.currentTimeMillis();
        disconnections++;
        prefs.edit()
                .putLong(KEY_LAST_DISCONNECTED, lastDisconnectedAt)
                .putInt(KEY_DISCONNECTIONS, disconnections)
                .apply();
    }

    public void recordPublished() {
        published++;
        prefs.edit().putInt(KEY_PUBLISHED, published).apply();
    }

    public void recordReceived() {
        received++;
        prefs.edit().putInt(KEY_RECEIVED, received).apply();
    }

    public void recordFailed() {
        failed++;
        prefs.edit().putInt(KEY_FAILED, failed).apply();
    }

    private void recordError(String message) {
        lastError = message;
        lastErrorAt = System.currentTimeMillis();
        prefs.edit()
                .putString(KEY_LAST_ERROR, lastError)
                .putLong(KEY_LAST_ERROR_AT, lastErrorAt)
                .apply();
    }

    // --- Lettura ---

    public List<Entry> getEntries() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    public Stats getStats() {
        return new Stats(published, received, failed, disconnections,
                lastConnectedAt, lastDisconnectedAt, lastErrorAt, lastError);
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        published = 0;
        received = 0;
        failed = 0;
        disconnections = 0;
        lastErrorAt = 0;
        lastError = null;
        prefs.edit()
                .putInt(KEY_PUBLISHED, 0)
                .putInt(KEY_RECEIVED, 0)
                .putInt(KEY_FAILED, 0)
                .putInt(KEY_DISCONNECTIONS, 0)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_LAST_ERROR_AT)
                .apply();
        io.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
            notifyChanged();
        });
    }

    // --- Listener ---

    public void addOnLogChangedListener(OnLogChangedListener listener) {
        listeners.add(listener);
    }

    public void removeOnLogChangedListener(OnLogChangedListener listener) {
        listeners.remove(listener);
    }

    /**
     * Le notifiche sono raggruppate: una full sync produce decine di eventi
     * ravvicinati e la vista non deve ricostruire la lista per ognuno.
     */
    private void notifyChanged() {
        if (listeners.isEmpty()) return;
        if (notifyScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(() -> {
                notifyScheduled.set(false);
                for (OnLogChangedListener listener : listeners) {
                    listener.onLogChanged();
                }
            }, NOTIFY_THROTTLE_MS);
        }
    }

    // --- Persistenza su file ---

    private void appendToFile(Entry entry) {
        try {
            if (logFile.length() > MAX_FILE_BYTES) {
                rewriteFile();
            }
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write(serialize(entry));
                writer.newLine();
            }
        } catch (IOException ignored) {
            // Il log diagnostico non deve mai far fallire la sincronizzazione
        }
    }

    /** Riscrive il file con le sole entry ancora in memoria (rotazione). */
    private void rewriteFile() {
        List<Entry> snapshot = getEntries();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, false))) {
            for (Entry entry : snapshot) {
                writer.write(serialize(entry));
                writer.newLine();
            }
        } catch (IOException ignored) {
        }
    }

    private void loadFromFile() {
        if (!logFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry entry = deserialize(line);
                if (entry == null) continue;
                synchronized (entries) {
                    entries.addLast(entry);
                    while (entries.size() > MAX_ENTRIES) {
                        entries.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            return;
        }
        notifyChanged();
    }

    private static String serialize(Entry entry) {
        return entry.timestamp + "|" + entry.level.name() + "|" + entry.category + "|"
                + entry.message.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static Entry deserialize(String line) {
        String[] parts = line.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            long timestamp = Long.parseLong(parts[0]);
            Level level = Level.valueOf(parts[1]);
            String message = parts[3].replace("\\n", "\n").replace("\\\\", "\\");
            return new Entry(timestamp, level, parts[2], message);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

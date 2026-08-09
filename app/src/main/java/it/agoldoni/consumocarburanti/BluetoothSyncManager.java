package it.agoldoni.consumocarburanti;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.gson.Gson;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sincronizzazione punto a punto delle auto in comune fra due installazioni
 * dell'app, su Bluetooth Classic (RFCOMM).
 *
 * <p>Una sessione e' one-shot e completamente guidata dall'utente: chi la avvia
 * ("mittente") propone un elenco di auto, chi la riceve conferma il codice di
 * verifica e sceglie quali accettare. Alla fine i dati viaggiano in entrambe le
 * direzioni, cosi' che i due telefoni abbiano lo stesso storico per le auto
 * concordate.
 *
 * <p>Il protocollo gira tutto su un thread dedicato con letture bloccanti: le
 * attese sulle decisioni dell'utente usano code, quelle sul socket un watchdog
 * che chiude il socket allo scadere del tempo (BluetoothSocket non espone un
 * timeout di lettura, ma chiudere il socket sblocca la {@code read} con una
 * IOException).
 */
public class BluetoothSyncManager {

    private static final String TAG = "BtSyncManager";

    /** Identifica il servizio RFCOMM: deve coincidere sulle due installazioni. */
    private static final UUID SERVICE_UUID =
            UUID.fromString("6c7f2a10-9d1b-4c53-8a4e-3f5b1d7e0c42");
    private static final String SERVICE_NAME = "ConsumoCarburantiSync";

    /** Tetto alla dimensione di un frame, a difesa da payload malformati. */
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private static final long TIMEOUT_ATTESA_S = 120;
    /**
     * Attesa di un messaggio che arriva solo dopo una decisione dell'utente
     * remoto: deve essere piu' lungo del tempo che quell'utente ha a
     * disposizione, altrimenti scade proprio mentre lui sta rispondendo.
     */
    private static final long TIMEOUT_ATTESA_REMOTA_S = 180;
    /**
     * Piu' lungo di quanto serva al solo trasferimento: comprende il tempo che
     * l'altro lato impiega a scrivere i dati ricevuti sul proprio database
     * prima di rispondere.
     */
    private static final long TIMEOUT_TRASFERIMENTO_S = 120;

    private static final String INTERRUZIONE_UTENTE = "UTENTE";

    private static volatile BluetoothSyncManager INSTANCE;

    public enum Fase {
        INATTIVA,
        ATTESA_CONNESSIONE,
        CONNESSIONE,
        VERIFICA,
        ATTESA_SCELTA,
        TRASFERIMENTO,
        CONCLUSA
    }

    /** Cosa e' cambiato in locale al termine di una sessione. */
    public static class Riepilogo {
        public int veicoliNuovi;
        public int veicoliAggiornati;
        public int veicoliCollegati;
        public int rifornimentiNuovi;
        public int rifornimentiAggiornati;
        public int rifornimentiScartati;
        public int rifornimentiInviati;
        /** Id locali sostituiti dall'adozione, da bonificare sul broker MQTT. */
        public final List<String> idSostituiti = new ArrayList<>();
    }

    public interface Callback {
        void onFase(Fase fase, String dettaglio);

        /** Va mostrato il codice; la risposta arriva con {@link #confermaCodice}. */
        void onCodice(String codice, String nomeRemoto);

        /** Vanno mostrate le auto proposte; la risposta con {@link #rispondiOfferta}. */
        void onOfferta(List<BtMessages.VeicoloOfferto> offerta);

        void onConclusa(Riepilogo riepilogo);

        void onErrore(String messaggio);

        void onAnnullata(String motivo);
    }

    /** Rifiuto esplicito arrivato dall'altro dispositivo. */
    private static class RifiutoRemoto extends Exception {
        RifiutoRemoto(String motivo) {
            super(motivo);
        }
    }

    private final Context appContext;
    private final AppDatabase db;
    private final SyncMerger merger;
    private final SyncLog syncLog;
    private final Gson gson;
    private final Handler mainHandler;
    private final ScheduledExecutorService watchdog;

    private final AtomicBoolean sessioneAttiva = new AtomicBoolean(false);
    private final BlockingQueue<Boolean> rispostaCodice = new ArrayBlockingQueue<>(1);
    private final BlockingQueue<List<BtMessages.AutoAccettata>> rispostaOfferta =
            new ArrayBlockingQueue<>(1);

    private volatile Callback callback;
    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket socket;
    private volatile ScheduledFuture<?> watchdogFuture;
    /** Null se la sessione procede, altrimenti perche' e' stata interrotta. */
    private volatile String interruzione;

    private BluetoothSyncManager(Context context) {
        appContext = context.getApplicationContext();
        db = AppDatabase.getInstance(appContext);
        merger = new SyncMerger(appContext);
        syncLog = SyncLog.getInstance(appContext);
        gson = new Gson();
        mainHandler = new Handler(Looper.getMainLooper());
        watchdog = Executors.newSingleThreadScheduledExecutor();
    }

    public static BluetoothSyncManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (BluetoothSyncManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new BluetoothSyncManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    // --- Permessi e adattatore ---

    /** I permessi runtime da chiedere, diversi prima e dopo Android 12. */
    public static String[] permessiNecessari() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN};
        }
        // BLUETOOTH e BLUETOOTH_ADMIN sono install-time; alla discovery serve la posizione
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean permessiConcessi(Context context) {
        for (String permesso : permessiNecessari()) {
            if (ContextCompat.checkSelfPermission(context, permesso)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static BluetoothAdapter adattatore(Context context) {
        BluetoothManager manager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    }

    // --- Ciclo di vita della sessione ---

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void clearCallback(Callback callback) {
        if (this.callback == callback) {
            this.callback = null;
        }
    }

    public boolean isSessioneAttiva() {
        return sessioneAttiva.get();
    }

    /** Avvia il ruolo di chi attende una connessione e riceve la proposta. */
    public void avviaComeRicevente() {
        avvia(false, null, null);
    }

    /** Avvia il ruolo di chi si connette e propone le auto indicate. */
    public void avviaComeMittente(BluetoothDevice device, List<String> veicoloIds) {
        avvia(true, device, new ArrayList<>(veicoloIds));
    }

    public void confermaCodice(boolean ok) {
        rispostaCodice.offer(ok);
    }

    /** Lista vuota per rifiutare l'intera proposta. */
    public void rispondiOfferta(List<BtMessages.AutoAccettata> scelte) {
        rispostaOfferta.offer(scelte != null ? scelte : Collections.emptyList());
    }

    public void annulla() {
        if (!sessioneAttiva.get()) return;
        if (interruzione == null) {
            interruzione = INTERRUZIONE_UTENTE;
        }
        syncLog.info(SyncLog.CAT_BT, "Sessione annullata dall'utente");
        // Sblocca sia le attese sulle decisioni sia le letture sul socket
        rispostaCodice.offer(Boolean.FALSE);
        rispostaOfferta.offer(Collections.emptyList());
        chiudiTutto();
    }

    private void avvia(boolean mittente, BluetoothDevice device, List<String> veicoloIds) {
        if (!sessioneAttiva.compareAndSet(false, true)) {
            notificaErrore(appContext.getString(R.string.bt_err_sessione_in_corso));
            return;
        }
        interruzione = null;
        rispostaCodice.clear();
        rispostaOfferta.clear();

        Thread sessione = new Thread(() -> {
            try {
                if (mittente) {
                    apriComeMittente(device);
                } else {
                    apriComeRicevente();
                }
                eseguiProtocollo(mittente, veicoloIds);
            } catch (RifiutoRemoto rifiuto) {
                notificaAnnullata(appContext.getString(
                        R.string.bt_annullata_da_remoto, rifiuto.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notificaAnnullata(appContext.getString(R.string.bt_annullata_generica));
            } catch (IOException e) {
                gestisciInterruzione(e);
            } catch (Exception e) {
                Log.e(TAG, "Errore imprevisto nella sessione Bluetooth", e);
                syncLog.error(SyncLog.CAT_BT, "Errore nella sessione", e);
                notificaErrore(SyncLog.describe(e));
            } finally {
                disarmaWatchdog();
                chiudiTutto();
                rispostaCodice.clear();
                rispostaOfferta.clear();
                sessioneAttiva.set(false);
            }
        }, "bt-sync");
        sessione.start();
    }

    /**
     * Una IOException puo' essere un guasto vero oppure la conseguenza della
     * chiusura del socket decisa da noi (annullamento o timeout): distinguerle
     * evita di mostrare all'utente un errore per una sua stessa scelta.
     */
    private void gestisciInterruzione(IOException e) {
        String causa = interruzione;
        if (INTERRUZIONE_UTENTE.equals(causa)) {
            notificaAnnullata(appContext.getString(R.string.bt_annullata_locale));
        } else if (causa != null) {
            syncLog.warn(SyncLog.CAT_BT, "Sessione interrotta: " + causa);
            notificaErrore(appContext.getString(R.string.bt_err_timeout, causa));
        } else {
            Log.w(TAG, "Sessione Bluetooth interrotta", e);
            syncLog.error(SyncLog.CAT_BT, "Sessione interrotta", e);
            notificaErrore(SyncLog.describe(e));
        }
    }

    // --- Apertura del canale ---

    @SuppressLint("MissingPermission")
    private void apriComeRicevente() throws IOException {
        BluetoothAdapter adapter = adapterAttivo();
        notificaFase(Fase.ATTESA_CONNESSIONE, null);
        syncLog.info(SyncLog.CAT_BT, "In attesa di una connessione in ingresso");

        BluetoothServerSocket inAscolto =
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
        serverSocket = inAscolto;

        armaWatchdog(TIMEOUT_ATTESA_S, appContext.getString(R.string.bt_fase_attesa_connessione));
        BluetoothSocket accettato;
        try {
            accettato = inAscolto.accept();
        } finally {
            disarmaWatchdog();
            chiudiServerSocket();
        }
        socket = accettato;
        syncLog.info(SyncLog.CAT_BT, "Connessione accettata da "
                + nomeDi(accettato.getRemoteDevice()));
    }

    @SuppressLint("MissingPermission")
    private void apriComeMittente(BluetoothDevice device) throws IOException {
        BluetoothAdapter adapter = adapterAttivo();
        // La discovery in corso rallenta o fa fallire la connessione
        if (adapter.isDiscovering()) {
            adapter.cancelDiscovery();
        }

        String nome = nomeDi(device);
        notificaFase(Fase.CONNESSIONE, nome);
        syncLog.info(SyncLog.CAT_BT, "Connessione a " + nome);

        BluetoothSocket nuovo = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
        socket = nuovo;

        armaWatchdog(TIMEOUT_ATTESA_S, appContext.getString(R.string.bt_fase_connessione));
        try {
            nuovo.connect();
        } finally {
            disarmaWatchdog();
        }
    }

    private BluetoothAdapter adapterAttivo() throws IOException {
        BluetoothAdapter adapter = adattatore(appContext);
        if (adapter == null) {
            throw new IOException(appContext.getString(R.string.bt_err_non_supportato));
        }
        if (!adapter.isEnabled()) {
            throw new IOException(appContext.getString(R.string.bt_err_spento));
        }
        return adapter;
    }

    @SuppressLint("MissingPermission")
    private String nomeDi(BluetoothDevice device) {
        if (device == null) return "?";
        try {
            String nome = device.getName();
            return nome != null && !nome.isEmpty() ? nome : device.getAddress();
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    @SuppressLint("MissingPermission")
    private String nomeLocale() {
        try {
            BluetoothAdapter adapter = adattatore(appContext);
            String nome = adapter != null ? adapter.getName() : null;
            if (nome != null && !nome.isEmpty()) return nome;
        } catch (SecurityException ignored) {
            // ricadiamo sul modello del dispositivo
        }
        return Build.MODEL;
    }

    // --- Protocollo ---

    private void eseguiProtocollo(boolean mittente, List<String> veicoloIds)
            throws IOException, InterruptedException, RifiutoRemoto {

        // La connessione puo' essere gia' stata chiusa da un annullamento o dal
        // watchdog mentre la si stava aprendo
        BluetoothSocket attivo = socket;
        if (attivo == null || interruzione != null) {
            throw new IOException("connessione chiusa");
        }
        DataInputStream in = new DataInputStream(attivo.getInputStream());
        DataOutputStream out = new DataOutputStream(attivo.getOutputStream());

        // 1. Handshake: ci si scambia nome e nonce
        notificaFase(Fase.VERIFICA, null);
        String nonceLocale = UUID.randomUUID().toString();
        BtMessages.Hello mio = new BtMessages.Hello(
                mittente ? BtMessages.TYPE_HELLO : BtMessages.TYPE_HELLO_ACK,
                nomeLocale(), nonceLocale);

        BtMessages.Hello suo;
        if (mittente) {
            invia(out, mio);
            suo = leggi(in, BtMessages.Hello.class, BtMessages.TYPE_HELLO_ACK,
                    TIMEOUT_ATTESA_S, R.string.bt_fase_handshake);
        } else {
            suo = leggi(in, BtMessages.Hello.class, BtMessages.TYPE_HELLO,
                    TIMEOUT_ATTESA_S, R.string.bt_fase_handshake);
            invia(out, mio);
        }

        if (suo.protocol != BtMessages.PROTOCOL_VERSION) {
            invia(out, new BtMessages.Reject(appContext.getString(R.string.bt_motivo_versione)));
            syncLog.error(SyncLog.CAT_BT, "Versione di protocollo incompatibile: remota "
                    + suo.protocol + ", locale " + BtMessages.PROTOCOL_VERSION);
            notificaErrore(appContext.getString(R.string.bt_err_versione));
            return;
        }

        // 2. Codice di verifica, da confermare su entrambi i telefoni
        String codice = BtMessages.codiceVerifica(nonceLocale, suo.nonce);
        syncLog.info(SyncLog.CAT_BT, "Sessione con " + suo.deviceName
                + ", codice di verifica " + codice);
        notificaCodice(codice, suo.deviceName);

        Boolean confermaLocale = rispostaCodice.poll(TIMEOUT_ATTESA_S, TimeUnit.SECONDS);
        if (confermaLocale == null || !confermaLocale) {
            invia(out, new BtMessages.Confirm(false));
            notificaAnnullata(appContext.getString(confermaLocale == null
                    ? R.string.bt_annullata_timeout
                    : R.string.bt_annullata_locale));
            return;
        }
        invia(out, new BtMessages.Confirm(true));

        BtMessages.Confirm confermaRemota = leggi(in, BtMessages.Confirm.class,
                BtMessages.TYPE_CONFIRM, TIMEOUT_ATTESA_REMOTA_S,
                R.string.bt_fase_conferma_codice);
        if (!confermaRemota.ok) {
            notificaAnnullata(appContext.getString(R.string.bt_annullata_codice_remoto));
            return;
        }

        // 3. Proposta, accettazione e scambio dei dati
        Riepilogo riepilogo = new Riepilogo();
        if (mittente) {
            scambioLatoMittente(in, out, veicoloIds, riepilogo);
        } else {
            scambioLatoRicevente(in, out, riepilogo);
        }
        // I due rami segnalano con interruzione l'uscita anticipata (rifiuto,
        // nessuna auto accettata, tempo scaduto): in quel caso non c'e' nulla
        // da chiudere in modo ordinato
        if (interruzione != null) return;

        // 4. Chiusura ordinata
        invia(out, new BtMessages.Done(
                riepilogo.veicoliNuovi + riepilogo.veicoliCollegati,
                riepilogo.rifornimentiNuovi + riepilogo.rifornimentiAggiornati));
        try {
            leggi(in, BtMessages.Done.class, BtMessages.TYPE_DONE,
                    TIMEOUT_TRASFERIMENTO_S, R.string.bt_fase_chiusura);
        } catch (Exception e) {
            // L'altro lato puo' aver gia' chiuso: i dati sono comunque applicati
            Log.w(TAG, "DONE remoto non ricevuto", e);
        }

        riallineaMqtt(riepilogo);
        syncLog.info(SyncLog.CAT_BT, "Sessione conclusa: " + descrivi(riepilogo));
        notificaConclusa(riepilogo);
    }

    private void scambioLatoMittente(DataInputStream in, DataOutputStream out,
                                     List<String> veicoloIds, Riepilogo riepilogo)
            throws IOException, RifiutoRemoto {

        notificaFase(Fase.ATTESA_SCELTA, null);
        invia(out, costruisciOfferta(veicoloIds));

        BtMessages.Accept accept = leggi(in, BtMessages.Accept.class, BtMessages.TYPE_ACCEPT,
                TIMEOUT_ATTESA_REMOTA_S, R.string.bt_fase_attesa_accettazione);
        if (accept.accettati == null || accept.accettati.isEmpty()) {
            String motivo = appContext.getString(R.string.bt_annullata_nessuna_auto);
            interruzione = motivo;
            notificaAnnullata(motivo);
            return;
        }

        List<String> condivisi = new ArrayList<>();
        for (BtMessages.AutoAccettata a : accept.accettati) {
            condivisi.add(a.remoteId);
        }

        notificaFase(Fase.TRASFERIMENTO, null);
        BtMessages.DataPayload miei = raccogli(condivisi);
        riepilogo.rifornimentiInviati = miei.rifornimenti.size();
        invia(out, miei);

        BtMessages.DataPayload suoi = leggi(in, BtMessages.DataPayload.class, BtMessages.TYPE_DATA,
                TIMEOUT_TRASFERIMENTO_S, R.string.bt_fase_ricezione);
        applica(suoi, condivisi, null, riepilogo);
    }

    private void scambioLatoRicevente(DataInputStream in, DataOutputStream out,
                                      Riepilogo riepilogo)
            throws IOException, InterruptedException, RifiutoRemoto {

        notificaFase(Fase.ATTESA_SCELTA, null);
        BtMessages.Offer offerta = leggi(in, BtMessages.Offer.class, BtMessages.TYPE_OFFER,
                TIMEOUT_ATTESA_REMOTA_S, R.string.bt_fase_attesa_offerta);
        notificaOfferta(offerta.veicoli != null ? offerta.veicoli : Collections.emptyList());

        List<BtMessages.AutoAccettata> scelte =
                rispostaOfferta.poll(TIMEOUT_ATTESA_S, TimeUnit.SECONDS);
        if (scelte == null || scelte.isEmpty()) {
            String motivo = appContext.getString(scelte == null
                    ? R.string.bt_annullata_timeout
                    : R.string.bt_annullata_nessuna_auto);
            invia(out, new BtMessages.Reject(motivo));
            interruzione = motivo;
            notificaAnnullata(motivo);
            return;
        }
        invia(out, new BtMessages.Accept(scelte));

        notificaFase(Fase.TRASFERIMENTO, null);
        BtMessages.DataPayload suoi = leggi(in, BtMessages.DataPayload.class, BtMessages.TYPE_DATA,
                TIMEOUT_TRASFERIMENTO_S, R.string.bt_fase_ricezione);

        List<String> condivisi = new ArrayList<>();
        for (BtMessages.AutoAccettata a : scelte) {
            condivisi.add(a.remoteId);
        }
        // L'adozione degli id avviene qui dentro, prima di rispondere: cosi' i
        // nostri rifornimenti partono gia' con il veicolo_id condiviso
        applica(suoi, condivisi, scelte, riepilogo);

        BtMessages.DataPayload miei = raccogli(condivisi);
        riepilogo.rifornimentiInviati = miei.rifornimenti.size();
        invia(out, miei);
    }

    // --- Dati ---

    private BtMessages.Offer costruisciOfferta(List<String> veicoloIds) {
        List<Veicolo> veicoli = db.veicoloDao().getByIds(veicoloIds);

        Map<String, RifornimentoDao.Stats> perVeicolo = new HashMap<>();
        for (RifornimentoDao.Stats s : db.rifornimentoDao().statsByVeicoli(veicoloIds)) {
            perVeicolo.put(s.veicoloId, s);
        }

        List<BtMessages.VeicoloOfferto> offerti = new ArrayList<>();
        for (Veicolo v : veicoli) {
            BtMessages.VeicoloOfferto o = new BtMessages.VeicoloOfferto();
            o.id = v.getId();
            o.nome = v.getNome();
            o.targa = v.getTarga();
            o.updatedAt = v.getUpdatedAt();
            RifornimentoDao.Stats s = perVeicolo.get(v.getId());
            if (s != null) {
                o.nRifornimenti = s.conteggio;
                o.primo = s.primo;
                o.ultimo = s.ultimo;
            }
            offerti.add(o);
        }
        syncLog.info(SyncLog.CAT_BT, "Proposte " + offerti.size() + " auto");
        return new BtMessages.Offer(offerti);
    }

    private BtMessages.DataPayload raccogli(List<String> veicoloIds) {
        return new BtMessages.DataPayload(
                db.veicoloDao().getByIds(veicoloIds),
                db.rifornimentoDao().getByVeicoli(veicoloIds));
    }

    /**
     * Applica i dati ricevuti, limitandoli alle auto concordate.
     *
     * @param scelte non null solo lato ricevente: porta le auto da collegare a
     *               una locale, che devono adottare l'id remoto prima del merge
     */
    private void applica(BtMessages.DataPayload dati, List<String> idConcordati,
                         List<BtMessages.AutoAccettata> scelte, Riepilogo riepilogo) {

        Set<String> ammessi = new HashSet<>(idConcordati);

        Map<String, Veicolo> veicoliRemoti = new LinkedHashMap<>();
        if (dati.veicoli != null) {
            for (Veicolo v : dati.veicoli) {
                if (ammessi.contains(v.getId())) {
                    veicoliRemoti.put(v.getId(), v);
                }
            }
        }

        if (scelte != null) {
            for (BtMessages.AutoAccettata a : scelte) {
                if (a.localId == null || a.localId.equals(a.remoteId)) continue;
                Veicolo remoto = veicoliRemoti.get(a.remoteId);
                if (remoto == null) continue;

                int spostati = merger.adottaIdVeicolo(a.localId, remoto);
                riepilogo.veicoliCollegati++;
                riepilogo.idSostituiti.add(a.localId);
                syncLog.info(SyncLog.CAT_BT, "Auto \"" + remoto.getNome()
                        + "\" collegata a quella locale: " + spostati
                        + " rifornimenti mantenuti");
            }
        }

        // Un'unica transazione invece di una per riga: con qualche migliaio di
        // rifornimenti la differenza e' fra secondi e minuti, e l'altro lato ci
        // sta aspettando
        db.runInTransaction(() -> {
            for (Veicolo v : veicoliRemoti.values()) {
                switch (merger.applyVeicolo(v)) {
                    case INSERITO:
                        riepilogo.veicoliNuovi++;
                        break;
                    case AGGIORNATO:
                        riepilogo.veicoliAggiornati++;
                        break;
                    default:
                        break;
                }
            }

            if (dati.rifornimenti != null) {
                for (Rifornimento r : dati.rifornimenti) {
                    if (r.getVeicoloId() == null || !ammessi.contains(r.getVeicoloId())) {
                        riepilogo.rifornimentiScartati++;
                        continue;
                    }
                    switch (merger.applyRifornimento(r)) {
                        case INSERITO:
                            riepilogo.rifornimentiNuovi++;
                            break;
                        case AGGIORNATO:
                            riepilogo.rifornimentiAggiornati++;
                            break;
                        case SCARTATO_FK:
                            riepilogo.rifornimentiScartati++;
                            break;
                        default:
                            break;
                    }
                }
            }
        });
    }

    /**
     * Se la sincronizzazione MQTT e' attiva, il broker non sa nulla di quanto
     * appena arrivato via Bluetooth: gli si ripubblica lo stato locale e si
     * cancellano i veicoli che l'adozione ha sostituito, altrimenti il loro
     * messaggio retained li farebbe ricomparire.
     */
    private void riallineaMqtt(Riepilogo riepilogo) {
        boolean qualcosaECambiato = riepilogo.veicoliNuovi + riepilogo.veicoliAggiornati
                + riepilogo.veicoliCollegati + riepilogo.rifornimentiNuovi
                + riepilogo.rifornimentiAggiornati > 0;
        if (!qualcosaECambiato) return;

        MqttSyncManager mqtt = MqttSyncManager.getInstance(appContext);
        if (!mqtt.isConnected()) return;

        mqtt.republishAll();
        for (String vecchioId : riepilogo.idSostituiti) {
            mqtt.publishDeleteVeicolo(vecchioId);
        }
        syncLog.info(SyncLog.CAT_BT, "Stato locale ripubblicato anche sul broker MQTT");
    }

    private String descrivi(Riepilogo r) {
        return r.veicoliNuovi + " auto nuove, " + r.veicoliCollegati + " collegate, "
                + r.rifornimentiNuovi + " rifornimenti nuovi, "
                + r.rifornimentiAggiornati + " aggiornati, "
                + r.rifornimentiScartati + " scartati, "
                + r.rifornimentiInviati + " inviati";
    }

    // --- Trasporto: frame lunghezza + JSON ---

    private void invia(DataOutputStream out, Object messaggio) throws IOException {
        byte[] payload = gson.toJson(messaggio).getBytes(StandardCharsets.UTF_8);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    private <T extends BtMessages.Busta> T leggi(DataInputStream in, Class<T> classe,
                                                 String tipoAtteso, long timeoutSecondi,
                                                 int faseResId)
            throws IOException, RifiutoRemoto {

        String fase = appContext.getString(faseResId);
        armaWatchdog(timeoutSecondi, fase);
        String json;
        try {
            int lunghezza = in.readInt();
            if (lunghezza < 0 || lunghezza > MAX_FRAME_BYTES) {
                throw new IOException("Frame di lunghezza non valida: " + lunghezza);
            }
            byte[] buffer = new byte[lunghezza];
            in.readFully(buffer);
            json = new String(buffer, StandardCharsets.UTF_8);
        } finally {
            disarmaWatchdog();
        }

        BtMessages.Busta busta = gson.fromJson(json, BtMessages.Busta.class);
        if (busta == null || busta.type == null) {
            throw new IOException("Messaggio senza tipo");
        }
        if (BtMessages.TYPE_REJECT.equals(busta.type) && !BtMessages.TYPE_REJECT.equals(tipoAtteso)) {
            BtMessages.Reject rifiuto = gson.fromJson(json, BtMessages.Reject.class);
            throw new RifiutoRemoto(rifiuto.motivo != null
                    ? rifiuto.motivo
                    : appContext.getString(R.string.bt_annullata_generica));
        }
        if (!tipoAtteso.equals(busta.type)) {
            throw new IOException("Atteso " + tipoAtteso + ", ricevuto " + busta.type);
        }
        T messaggio = gson.fromJson(json, classe);
        if (messaggio == null) {
            throw new IOException("Messaggio " + tipoAtteso + " non leggibile");
        }
        return messaggio;
    }

    // --- Watchdog e chiusura ---

    /**
     * BluetoothSocket non ha un timeout di lettura: l'unico modo per sbloccare
     * una {@code read} che non arrivera' mai e' chiudere il socket da un altro
     * thread, cosa che la fa terminare con IOException.
     */
    private void armaWatchdog(long secondi, String fase) {
        disarmaWatchdog();
        watchdogFuture = watchdog.schedule(() -> {
            if (interruzione == null) {
                interruzione = fase;
            }
            Log.w(TAG, "Timeout in fase: " + fase);
            chiudiTutto();
        }, secondi, TimeUnit.SECONDS);
    }

    private void disarmaWatchdog() {
        ScheduledFuture<?> corrente = watchdogFuture;
        if (corrente != null) {
            corrente.cancel(false);
            watchdogFuture = null;
        }
    }

    private void chiudiTutto() {
        chiudiServerSocket();
        BluetoothSocket corrente = socket;
        socket = null;
        if (corrente != null) {
            try {
                corrente.close();
            } catch (IOException e) {
                Log.d(TAG, "Chiusura socket", e);
            }
        }
    }

    private void chiudiServerSocket() {
        BluetoothServerSocket corrente = serverSocket;
        serverSocket = null;
        if (corrente != null) {
            try {
                corrente.close();
            } catch (IOException e) {
                Log.d(TAG, "Chiusura server socket", e);
            }
        }
    }

    // --- Notifiche verso la UI ---

    private void notificaFase(Fase fase, String dettaglio) {
        Callback c = callback;
        if (c != null) mainHandler.post(() -> c.onFase(fase, dettaglio));
    }

    private void notificaCodice(String codice, String nomeRemoto) {
        Callback c = callback;
        if (c != null) mainHandler.post(() -> c.onCodice(codice, nomeRemoto));
    }

    private void notificaOfferta(List<BtMessages.VeicoloOfferto> offerta) {
        Callback c = callback;
        if (c != null) mainHandler.post(() -> c.onOfferta(offerta));
    }

    private void notificaConclusa(Riepilogo riepilogo) {
        Callback c = callback;
        if (c != null) mainHandler.post(() -> c.onConclusa(riepilogo));
    }

    private void notificaErrore(String messaggio) {
        Callback c = callback;
        if (c != null) mainHandler.post(() -> c.onErrore(messaggio));
    }

    private void notificaAnnullata(String motivo) {
        syncLog.info(SyncLog.CAT_BT, "Sessione non completata: " + motivo);
        Callback c = callback;
        if (c != null) mainHandler.post(() -> c.onAnnullata(motivo));
    }
}

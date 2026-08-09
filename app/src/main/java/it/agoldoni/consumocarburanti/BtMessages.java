package it.agoldoni.consumocarburanti;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Messaggi del protocollo di sincronizzazione Bluetooth. Viaggiano come JSON
 * dentro frame lunghezza + payload (vedi {@link BluetoothSyncManager}).
 *
 * <p>Ogni messaggio porta il campo {@code type}: chi riceve lo legge prima come
 * {@link Busta} per capire di cosa si tratta, poi rilegge lo stesso JSON nella
 * classe concreta. Tutte le classi hanno un costruttore vuoto perche' Gson lo
 * usa in deserializzazione.
 */
public final class BtMessages {

    /** Incrementare a ogni modifica incompatibile del protocollo. */
    public static final int PROTOCOL_VERSION = 1;

    public static final String TYPE_HELLO = "HELLO";
    public static final String TYPE_HELLO_ACK = "HELLO_ACK";
    public static final String TYPE_CONFIRM = "CONFIRM";
    public static final String TYPE_OFFER = "OFFER";
    public static final String TYPE_ACCEPT = "ACCEPT";
    public static final String TYPE_REJECT = "REJECT";
    public static final String TYPE_DATA = "DATA";
    public static final String TYPE_DONE = "DONE";

    private BtMessages() {
    }

    public static class Busta {
        public String type;

        public Busta() {
        }

        Busta(String type) {
            this.type = type;
        }
    }

    public static class Hello extends Busta {
        public int protocol;
        public String deviceName;
        public String nonce;

        public Hello() {
        }

        public Hello(String type, String deviceName, String nonce) {
            super(type);
            this.protocol = PROTOCOL_VERSION;
            this.deviceName = deviceName;
            this.nonce = nonce;
        }
    }

    public static class Confirm extends Busta {
        public boolean ok;

        public Confirm() {
        }

        public Confirm(boolean ok) {
            super(TYPE_CONFIRM);
            this.ok = ok;
        }
    }

    /** Descrizione di un'auto proposta, senza i rifornimenti. */
    public static class VeicoloOfferto {
        public String id;
        public String nome;
        public String targa;
        public long updatedAt;
        public int nRifornimenti;
        public long primo;
        public long ultimo;
    }

    public static class Offer extends Busta {
        public List<VeicoloOfferto> veicoli;

        public Offer() {
        }

        public Offer(List<VeicoloOfferto> veicoli) {
            super(TYPE_OFFER);
            this.veicoli = veicoli;
        }
    }

    /** Un'auto accettata da chi riceve. */
    public static class AutoAccettata {
        public String remoteId;
        /**
         * Id dell'auto locale a cui collegare quella proposta, oppure null per
         * crearne una nuova. Quando e' valorizzato, chi riceve fa adottare alla
         * propria auto l'id remoto.
         */
        public String localId;

        public AutoAccettata() {
        }

        public AutoAccettata(String remoteId, String localId) {
            this.remoteId = remoteId;
            this.localId = localId;
        }
    }

    public static class Accept extends Busta {
        public List<AutoAccettata> accettati;

        public Accept() {
        }

        public Accept(List<AutoAccettata> accettati) {
            super(TYPE_ACCEPT);
            this.accettati = accettati;
        }
    }

    public static class Reject extends Busta {
        public String motivo;

        public Reject() {
        }

        public Reject(String motivo) {
            super(TYPE_REJECT);
            this.motivo = motivo;
        }
    }

    public static class DataPayload extends Busta {
        public List<Veicolo> veicoli;
        public List<Rifornimento> rifornimenti;

        public DataPayload() {
        }

        public DataPayload(List<Veicolo> veicoli, List<Rifornimento> rifornimenti) {
            super(TYPE_DATA);
            this.veicoli = veicoli;
            this.rifornimenti = rifornimenti;
        }
    }

    public static class Done extends Busta {
        public int veicoli;
        public int rifornimenti;

        public Done() {
        }

        public Done(int veicoli, int rifornimenti) {
            super(TYPE_DONE);
            this.veicoli = veicoli;
            this.rifornimenti = rifornimenti;
        }
    }

    /**
     * Codice a 6 cifre che i due utenti confrontano a voce prima di procedere.
     * I nonce vengono concatenati in ordine lessicografico cosi' che il calcolo
     * dia lo stesso risultato indipendentemente dal ruolo di chi lo esegue.
     *
     * <p>Serve a scoprire di essersi collegati al telefono sbagliato, non a
     * proteggere il canale: la riservatezza la garantisce gia' il socket RFCOMM
     * secure, cifrato a livello di sistema.
     */
    public static String codiceVerifica(String nonceA, String nonceB) {
        boolean aPrima = nonceA.compareTo(nonceB) <= 0;
        String concatenati = aPrima ? nonceA + nonceB : nonceB + nonceA;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(concatenati.getBytes("UTF-8"));
            int valore = ((hash[0] & 0xFF) << 16) | ((hash[1] & 0xFF) << 8) | (hash[2] & 0xFF);
            return String.format(Locale.ITALY, "%06d", valore % 1000000);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // SHA-256 e UTF-8 sono garantiti da ogni JVM Android
            throw new IllegalStateException(e);
        }
    }
}

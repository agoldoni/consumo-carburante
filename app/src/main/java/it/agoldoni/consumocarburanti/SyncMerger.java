package it.agoldoni.consumocarburanti;

import android.content.Context;

/**
 * Fusione di un record proveniente da un'altra installazione dell'app dentro il
 * database locale. La regola e' last-write-wins su {@code updatedAt} e vale
 * identica per tutti i canali di sincronizzazione (MQTT e Bluetooth): tenerla
 * in un unico punto evita che i due canali divergano.
 *
 * <p>Il merge non cancella mai nulla: le eliminazioni sono un'operazione a
 * parte ({@link #deleteVeicolo}, {@link #deleteRifornimento},
 * {@link #deleteManutenzione}) che solo MQTT usa, tramite i messaggi con
 * payload vuoto.
 */
public class SyncMerger {

    public enum Esito {
        /** Il record non esisteva in locale ed e' stato inserito. */
        INSERITO,
        /** Il record remoto era piu' recente e ha sovrascritto quello locale. */
        AGGIORNATO,
        /** Il record locale era gia' allineato (o piu' recente): nessuna modifica. */
        GIA_ALLINEATO,
        /** Record scartato perche' il suo veicolo non esiste in locale. */
        SCARTATO_FK,
        /** Record scartato perche' privo di un campo obbligatorio. */
        SCARTATO_INVALIDO
    }

    /** Quanti record hanno seguito l'auto nel cambio di identificativo. */
    public static class Spostamento {
        public int rifornimenti;
        public int manutenzioni;
    }

    private final AppDatabase db;

    public SyncMerger(Context context) {
        this.db = AppDatabase.getInstance(context);
    }

    public Esito applyVeicolo(Veicolo remote) {
        Veicolo local = db.veicoloDao().getById(remote.getId());

        if (local == null) {
            db.veicoloDao().insert(remote);
            return Esito.INSERITO;
        }

        if (remote.getUpdatedAt() > local.getUpdatedAt()) {
            local.setNome(remote.getNome());
            local.setTarga(remote.getTarga());
            local.setUpdatedAt(remote.getUpdatedAt());
            db.veicoloDao().update(local);
            return Esito.AGGIORNATO;
        }

        return Esito.GIA_ALLINEATO;
    }

    public Esito applyRifornimento(Rifornimento remote) {
        if (remote.getVeicoloId() != null
                && db.veicoloDao().getById(remote.getVeicoloId()) == null) {
            return Esito.SCARTATO_FK;
        }

        Rifornimento local = db.rifornimentoDao().getById(remote.getId());

        if (local == null) {
            db.rifornimentoDao().insert(remote);
            return Esito.INSERITO;
        }

        if (remote.getUpdatedAt() > local.getUpdatedAt()) {
            local.setDatetime(remote.getDatetime());
            local.setKm(remote.getKm());
            local.setQtaBenzina(remote.getQtaBenzina());
            local.setCosto(remote.getCosto());
            local.setVeicoloId(remote.getVeicoloId());
            local.setLatitude(remote.getLatitude());
            local.setLongitude(remote.getLongitude());
            local.setUpdatedAt(remote.getUpdatedAt());
            db.rifornimentoDao().update(local);
            return Esito.AGGIORNATO;
        }

        return Esito.GIA_ALLINEATO;
    }

    public Esito applyManutenzione(Manutenzione remote) {
        // Gson lascia null i campi che il JSON non porta: senza questo controllo
        // il NOT NULL farebbe fallire l'insert e, nel Bluetooth, annullerebbe
        // l'intera transazione della sessione
        if (remote.getId() == null || remote.getData() == null || remote.getTipo() == null) {
            return Esito.SCARTATO_INVALIDO;
        }

        if (remote.getVeicoloId() != null
                && db.veicoloDao().getById(remote.getVeicoloId()) == null) {
            return Esito.SCARTATO_FK;
        }

        Manutenzione local = db.manutenzioneDao().getById(remote.getId());

        if (local == null) {
            db.manutenzioneDao().insert(remote);
            return Esito.INSERITO;
        }

        if (remote.getUpdatedAt() > local.getUpdatedAt()) {
            local.setData(remote.getData());
            local.setTipo(remote.getTipo());
            local.setDescrizione(remote.getDescrizione());
            local.setKm(remote.getKm());
            local.setCosto(remote.getCosto());
            local.setVeicoloId(remote.getVeicoloId());
            local.setUpdatedAt(remote.getUpdatedAt());
            db.manutenzioneDao().update(local);
            return Esito.AGGIORNATO;
        }

        return Esito.GIA_ALLINEATO;
    }

    /** @return il veicolo eliminato, oppure null se non era presente. */
    public Veicolo deleteVeicolo(String id) {
        Veicolo existing = db.veicoloDao().getById(id);
        if (existing != null) {
            db.veicoloDao().delete(existing);
        }
        return existing;
    }

    /** @return il rifornimento eliminato, oppure null se non era presente. */
    public Rifornimento deleteRifornimento(String id) {
        Rifornimento existing = db.rifornimentoDao().getById(id);
        if (existing != null) {
            db.rifornimentoDao().delete(existing);
        }
        return existing;
    }

    /** @return la manutenzione eliminata, oppure null se non era presente. */
    public Manutenzione deleteManutenzione(String id) {
        Manutenzione existing = db.manutenzioneDao().getById(id);
        if (existing != null) {
            db.manutenzioneDao().delete(existing);
        }
        return existing;
    }

    /**
     * Fa adottare al veicolo locale {@code idLocale} l'identificativo del
     * veicolo {@code remoto}, cosi' che le due installazioni indichino la stessa
     * auto con lo stesso id e da qui in avanti si riconoscano da sole.
     *
     * <p>SQLite non permette di cambiare una chiave primaria referenziata:
     * l'ordine delle operazioni e' vincolante. Il nuovo veicolo va inserito
     * prima di ripuntare rifornimenti e manutenzioni (vincolo di foreign key) e
     * il vecchio va eliminato dopo, quando non ha piu' figli, altrimenti la
     * {@code ON DELETE CASCADE} porterebbe via lo storico. Ogni tabella figlia
     * aggiunta in futuro va ripuntata qui.
     *
     * @return quanti record sono stati ripuntati sul nuovo id
     */
    public Spostamento adottaIdVeicolo(String idLocale, Veicolo remoto) {
        final Spostamento spostati = new Spostamento();
        if (idLocale.equals(remoto.getId())) {
            // Le due installazioni usano gia' lo stesso id: il normale merge basta
            applyVeicolo(remoto);
            return spostati;
        }

        db.runInTransaction(() -> {
            Veicolo vecchio = db.veicoloDao().getById(idLocale);

            if (db.veicoloDao().getById(remoto.getId()) == null) {
                db.veicoloDao().insert(fondi(vecchio, remoto));
            } else {
                applyVeicolo(remoto);
            }

            long quando = System.currentTimeMillis();
            spostati.rifornimenti = db.rifornimentoDao().reassignVeicolo(
                    idLocale, remoto.getId(), quando);
            spostati.manutenzioni = db.manutenzioneDao().reassignVeicolo(
                    idLocale, remoto.getId(), quando);

            if (vecchio != null) {
                db.veicoloDao().delete(vecchio);
            }
        });
        return spostati;
    }

    /**
     * Il veicolo da inserire con l'id remoto: nome e targa sono quelli piu'
     * recenti fra i due, cosi' il collegamento non fa perdere una modifica
     * fatta in locale solo perche' e' l'id remoto a prevalere.
     */
    private static Veicolo fondi(Veicolo locale, Veicolo remoto) {
        if (locale == null || remoto.getUpdatedAt() >= locale.getUpdatedAt()) {
            return remoto;
        }
        Veicolo fuso = new Veicolo();
        fuso.setId(remoto.getId());
        fuso.setNome(locale.getNome());
        fuso.setTarga(locale.getTarga());
        fuso.setUpdatedAt(locale.getUpdatedAt());
        return fuso;
    }
}

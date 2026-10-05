package it.agoldoni.consumocarburanti;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * A parita' di giorno l'ordine segue {@code updatedAt}, cosi' piu' interventi
 * nella stessa data non cambiano posizione a ogni ricarica dell'elenco.
 */
@Dao
public interface ManutenzioneDao {

    @Query("SELECT * FROM manutenzioni ORDER BY data DESC, updatedAt DESC")
    List<Manutenzione> getAll();

    @Query("SELECT * FROM manutenzioni WHERE veicolo_id = :veicoloId ORDER BY data DESC, updatedAt DESC")
    List<Manutenzione> getByVeicolo(String veicoloId);

    @Query("SELECT * FROM manutenzioni WHERE id = :id")
    Manutenzione getById(String id);

    @Query("SELECT * FROM manutenzioni WHERE veicolo_id IN (:veicoloIds)")
    List<Manutenzione> getByVeicoli(List<String> veicoloIds);

    /** Numero di interventi per veicolo, per descrivere le auto offerte via Bluetooth. */
    @Query("SELECT veicolo_id AS veicoloId, COUNT(*) AS conteggio " +
            "FROM manutenzioni WHERE veicolo_id IN (:veicoloIds) GROUP BY veicolo_id")
    List<Conteggio> countByVeicoli(List<String> veicoloIds);

    @Insert
    void insert(Manutenzione manutenzione);

    @Update
    void update(Manutenzione manutenzione);

    @Delete
    void delete(Manutenzione manutenzione);

    /**
     * Sposta gli interventi da un veicolo all'altro, come
     * {@link RifornimentoDao#reassignVeicolo}: {@code updatedAt} cambia perche'
     * il nuovo {@code veicolo_id} deve propagarsi agli altri canali di sync.
     *
     * @return il numero di righe modificate
     */
    @Query("UPDATE manutenzioni SET veicolo_id = :nuovoId, updatedAt = :quando " +
            "WHERE veicolo_id = :vecchioId")
    int reassignVeicolo(String vecchioId, String nuovoId, long quando);

    class Conteggio {
        public String veicoloId;
        public int conteggio;
    }
}

package it.agoldoni.consumocarburanti;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RifornimentoDao {

    @Query("SELECT * FROM rifornimenti ORDER BY datetime DESC")
    List<Rifornimento> getAll();

    @Insert
    void insert(Rifornimento rifornimento);

    @Delete
    void delete(Rifornimento rifornimento);

    @Update
    void update(Rifornimento rifornimento);

    @Query("SELECT * FROM rifornimenti WHERE veicolo_id = :veicoloId ORDER BY datetime DESC")
    List<Rifornimento> getByVeicolo(String veicoloId);

    @Query("SELECT * FROM rifornimenti WHERE id = :id")
    Rifornimento getById(String id);

    @Query("SELECT * FROM rifornimenti WHERE veicolo_id IN (:veicoloIds) ORDER BY datetime DESC")
    List<Rifornimento> getByVeicoli(List<String> veicoloIds);

    /**
     * Conteggio e periodo coperto per ciascun veicolo, per descrivere le auto
     * offerte in una sessione Bluetooth senza caricarne i rifornimenti.
     */
    @Query("SELECT veicolo_id AS veicoloId, COUNT(*) AS conteggio, " +
            "MIN(datetime) AS primo, MAX(datetime) AS ultimo " +
            "FROM rifornimenti WHERE veicolo_id IN (:veicoloIds) GROUP BY veicolo_id")
    List<Stats> statsByVeicoli(List<String> veicoloIds);

    /**
     * Sposta i rifornimenti da un veicolo all'altro. Usato quando il veicolo
     * locale adotta l'identificativo di quello remoto. Il record e' cambiato
     * davvero, quindi {@code updatedAt} viene aggiornato: e' cosi' che il nuovo
     * {@code veicolo_id} si propaga anche agli altri canali di sync.
     *
     * @return il numero di righe modificate
     */
    @Query("UPDATE rifornimenti SET veicolo_id = :nuovoId, updatedAt = :quando " +
            "WHERE veicolo_id = :vecchioId")
    int reassignVeicolo(String vecchioId, String nuovoId, long quando);

    class Stats {
        public String veicoloId;
        public int conteggio;
        public long primo;
        public long ultimo;
    }
}

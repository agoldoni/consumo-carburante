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
}

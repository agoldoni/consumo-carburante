package it.agoldoni.consumocarburanti;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface VeicoloDao {

    @Query("SELECT * FROM veicoli ORDER BY nome ASC")
    List<Veicolo> getAll();

    @Query("SELECT * FROM veicoli WHERE id = :id")
    Veicolo getById(String id);

    @Insert
    void insert(Veicolo veicolo);

    @Update
    void update(Veicolo veicolo);

    @Delete
    void delete(Veicolo veicolo);

    @Query("SELECT COUNT(*) FROM veicoli")
    int count();
}

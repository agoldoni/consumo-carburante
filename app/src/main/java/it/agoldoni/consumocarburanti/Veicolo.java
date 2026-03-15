package it.agoldoni.consumocarburanti;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.UUID;

@Entity(tableName = "veicoli")
public class Veicolo {

    @PrimaryKey
    @NonNull
    private String id;

    private String nome;

    private String targa;

    public Veicolo() {
        this.id = UUID.randomUUID().toString();
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getTarga() {
        return targa;
    }

    public void setTarga(String targa) {
        this.targa = targa;
    }

    @NonNull
    @Override
    public String toString() {
        return nome != null ? nome : "";
    }
}

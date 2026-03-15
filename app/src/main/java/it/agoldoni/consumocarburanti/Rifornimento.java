package it.agoldoni.consumocarburanti;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.UUID;

@Entity(tableName = "rifornimenti",
        indices = @Index("veicolo_id"),
        foreignKeys = @ForeignKey(
                entity = Veicolo.class,
                parentColumns = "id",
                childColumns = "veicolo_id",
                onDelete = ForeignKey.CASCADE))
public class Rifornimento {

    @PrimaryKey
    @NonNull
    private String id;

    private long datetime;

    private int km;

    @ColumnInfo(name = "qta_benzina")
    private double qtaBenzina;

    private double costo;

    @ColumnInfo(name = "veicolo_id")
    private String veicoloId;

    @ColumnInfo(name = "latitude")
    private Double latitude;

    @ColumnInfo(name = "longitude")
    private Double longitude;

    public Rifornimento() {
        this.id = UUID.randomUUID().toString();
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    public long getDatetime() {
        return datetime;
    }

    public void setDatetime(long datetime) {
        this.datetime = datetime;
    }

    public int getKm() {
        return km;
    }

    public void setKm(int km) {
        this.km = km;
    }

    public double getQtaBenzina() {
        return qtaBenzina;
    }

    public void setQtaBenzina(double qtaBenzina) {
        this.qtaBenzina = qtaBenzina;
    }

    public double getCosto() {
        return costo;
    }

    public void setCosto(double costo) {
        this.costo = costo;
    }

    public String getVeicoloId() {
        return veicoloId;
    }

    public void setVeicoloId(String veicoloId) {
        this.veicoloId = veicoloId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }
}

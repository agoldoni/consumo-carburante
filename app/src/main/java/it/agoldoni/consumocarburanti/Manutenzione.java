package it.agoldoni.consumocarburanti;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

/**
 * Intervento di manutenzione su un'auto. Ricalca {@link Rifornimento} (UUID,
 * {@code updatedAt}, foreign key in cascade) per viaggiare sugli stessi canali
 * di sincronizzazione con la stessa regola di merge.
 *
 * <p>La data e' solo un giorno, salvata come stringa ISO {@code yyyy-MM-dd}: un
 * epoch a mezzanotte dipenderebbe dal fuso del telefono che l'ha scritta, e lo
 * stesso intervento potrebbe cadere in giorni diversi su due dispositivi.
 * L'ordine lessicografico della stringa coincide con quello cronologico.
 */
@Entity(tableName = "manutenzioni",
        indices = @Index("veicolo_id"),
        foreignKeys = @ForeignKey(
                entity = Veicolo.class,
                parentColumns = "id",
                childColumns = "veicolo_id",
                onDelete = ForeignKey.CASCADE))
public class Manutenzione {

    @PrimaryKey
    @NonNull
    private String id;

    /** Giorno dell'intervento, {@code yyyy-MM-dd}. */
    @NonNull
    private String data;

    /** Nome di una costante di {@link TipoManutenzione}. */
    @NonNull
    private String tipo;

    private String descrizione;

    /** Lettura del contachilometri, null se non indicata. */
    private Integer km;

    private double costo;

    @ColumnInfo(name = "veicolo_id")
    private String veicoloId;

    private long updatedAt;

    /**
     * Data e tipo restano null: Gson usa questo costruttore e, se il JSON
     * ricevuto non li porta, il merge deve poterlo scoprire invece di trovarli
     * riempiti con un valore di comodo.
     */
    @SuppressWarnings("ConstantConditions")
    public Manutenzione() {
        this.id = UUID.randomUUID().toString();
        this.updatedAt = System.currentTimeMillis();
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull String id) {
        this.id = id;
    }

    @NonNull
    public String getData() {
        return data;
    }

    public void setData(@NonNull String data) {
        this.data = data;
    }

    @NonNull
    public String getTipo() {
        return tipo;
    }

    public void setTipo(@NonNull String tipo) {
        this.tipo = tipo;
    }

    public String getDescrizione() {
        return descrizione;
    }

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public Integer getKm() {
        return km;
    }

    public void setKm(Integer km) {
        this.km = km;
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

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    // --- Date "solo giorno" ---

    /** @param mese da 0 a 11, come in {@link Calendar} e nel DatePicker */
    public static String dataIso(int anno, int mese, int giorno) {
        return String.format(Locale.US, "%04d-%02d-%02d", anno, mese + 1, giorno);
    }

    public static String oggiIso() {
        Calendar c = Calendar.getInstance();
        return dataIso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * Anno, mese (0-11) e giorno di una data ISO, per inizializzare il
     * DatePicker. Se la stringa non e' leggibile restituisce la data di oggi.
     */
    public static int[] componenti(String iso) {
        try {
            String[] parti = iso.split("-");
            return new int[]{
                    Integer.parseInt(parti[0]),
                    Integer.parseInt(parti[1]) - 1,
                    Integer.parseInt(parti[2])};
        } catch (RuntimeException e) {
            Calendar c = Calendar.getInstance();
            return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)};
        }
    }

    /** {@code yyyy-MM-dd} → {@code dd/MM/yyyy}; una stringa inattesa resta com'e'. */
    public static String dataVisualizzata(String iso) {
        if (iso == null || iso.length() != 10) return iso != null ? iso : "";
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }
}

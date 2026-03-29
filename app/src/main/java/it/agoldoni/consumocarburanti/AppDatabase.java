package it.agoldoni.consumocarburanti;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Rifornimento.class, Veicolo.class}, version = 5, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract RifornimentoDao rifornimentoDao();
    public abstract VeicoloDao veicoloDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Create veicoli table
            database.execSQL("CREATE TABLE IF NOT EXISTS veicoli (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "nome TEXT, " +
                    "targa TEXT)");

            // Insert default vehicle
            database.execSQL("INSERT INTO veicoli (id, nome, targa) " +
                    "VALUES ('default-vehicle-id', 'La mia auto', '')");

            // Recreate rifornimenti with foreign key (SQLite ALTER TABLE cannot add FK)
            database.execSQL("CREATE TABLE rifornimenti_new (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "datetime INTEGER NOT NULL, " +
                    "km INTEGER NOT NULL, " +
                    "qta_benzina REAL NOT NULL, " +
                    "costo REAL NOT NULL, " +
                    "veicolo_id TEXT, " +
                    "FOREIGN KEY(veicolo_id) REFERENCES veicoli(id) ON DELETE CASCADE)");

            // Copy existing data, assigning default vehicle
            database.execSQL("INSERT INTO rifornimenti_new (id, datetime, km, qta_benzina, costo, veicolo_id) " +
                    "SELECT id, datetime, km, qta_benzina, costo, 'default-vehicle-id' FROM rifornimenti");

            // Swap tables
            database.execSQL("DROP TABLE rifornimenti");
            database.execSQL("ALTER TABLE rifornimenti_new RENAME TO rifornimenti");

            // Create index
            database.execSQL("CREATE INDEX IF NOT EXISTS index_rifornimenti_veicolo_id ON rifornimenti(veicolo_id)");
        }
    };

    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE rifornimenti ADD COLUMN latitude REAL");
            database.execSQL("ALTER TABLE rifornimenti ADD COLUMN longitude REAL");
        }
    };

    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE veicoli ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE rifornimenti ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "consumo_carburanti_db"
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                     .build();
                }
            }
        }
        return INSTANCE;
    }
}

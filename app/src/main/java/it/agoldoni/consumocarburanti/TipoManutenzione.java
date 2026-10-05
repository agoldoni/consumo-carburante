package it.agoldoni.consumocarburanti;

import androidx.annotation.StringRes;

/**
 * Tipi di intervento di manutenzione. Nel database e nei messaggi di sync si
 * salva il nome della costante, non l'ordinale: l'ordine qui sotto e' solo
 * quello del menu a tendina e si puo' cambiare senza toccare i dati.
 */
public enum TipoManutenzione {
    ALTRO(R.string.tipo_altro),
    TAGLIANDO(R.string.tipo_tagliando),
    REVISIONE(R.string.tipo_revisione);

    @StringRes
    private final int etichetta;

    TipoManutenzione(@StringRes int etichetta) {
        this.etichetta = etichetta;
    }

    @StringRes
    public int getEtichetta() {
        return etichetta;
    }

    /**
     * Il tipo corrispondente al codice salvato. Un codice sconosciuto, per
     * esempio un tipo aggiunto da una versione piu' recente dell'app su un
     * altro telefono, si mostra come {@link #ALTRO}: il chiamante non deve
     * pero' riscriverlo, per non perdere il valore originale.
     */
    public static TipoManutenzione fromCodice(String codice) {
        if (codice != null) {
            for (TipoManutenzione t : values()) {
                if (t.name().equals(codice)) {
                    return t;
                }
            }
        }
        return ALTRO;
    }
}

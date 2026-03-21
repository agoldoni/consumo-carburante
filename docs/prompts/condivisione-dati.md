# Feature: Condivisione Dati

## Descrizione

Aggiungere un bottone nella toolbar (in alto a destra) della `MainActivity` per esportare i rifornimenti del veicolo selezionato in formato CSV e condividerli tramite il sistema di sharing nativo di Android (Intent.ACTION_SEND / ShareSheet).

L'utente preme il bottone, l'app genera un file CSV con tutti i rifornimenti del veicolo corrente, e apre il chooser di Android per selezionare la piattaforma di condivisione (email, WhatsApp, Telegram, Drive, ecc.).

## Requisiti

1. **Bottone in toolbar**: icona "share" nel menu della toolbar, visibile in alto a destra
2. **Esportazione CSV**: generare un file CSV con header e tutti i rifornimenti del veicolo selezionato
3. **Formato CSV**:
   - Header: `Data,Km,Litri,Costo,Latitudine,Longitudine`
   - Date formattate in formato italiano (`dd/MM/yyyy HH:mm`)
   - Separatore: virgola
   - Encoding: UTF-8
4. **Nome file**: `rifornimenti_<nome_veicolo>_<data_export>.csv`
5. **Condivisione**: usare `Intent.ACTION_SEND` con MIME type `text/csv` + `Intent.createChooser()` per mostrare il selettore di piattaforma
6. **FileProvider**: usare `FileProvider` per condividere il file in modo sicuro (scrivere in cache dir)

## Piano di implementazione

### Step 1: Risorse menu e stringhe
- Creare `res/menu/menu_main.xml` con item share
- Aggiungere stringhe necessarie in `strings.xml`
- Aggiungere icona share (usare icona Material built-in)

### Step 2: FileProvider
- Aggiungere `FileProvider` nel `AndroidManifest.xml`
- Creare `res/xml/file_paths.xml` con path per cache directory

### Step 3: Logica di esportazione CSV
- In `MainActivity`, implementare `onCreateOptionsMenu()` per inflare il menu
- Implementare `onOptionsItemSelected()` per gestire il click
- Metodo `esportaCSV()`:
  1. Leggere i rifornimenti del veicolo corrente dal DB (background thread)
  2. Generare il file CSV nella cache directory
  3. Ottenere URI tramite FileProvider
  4. Lanciare Intent.ACTION_SEND con chooser

### Step 4: Documentazione
- Documentare la feature in `docs/features/condivisione-dati.md`

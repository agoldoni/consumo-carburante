# Feature: Import Data

## Descrizione

Aggiungere la possibilita di importare rifornimenti da un file CSV con lo stesso formato usato nell'esportazione. Se il veicolo indicato nel CSV non esiste, viene creato automaticamente. Gli UUID vengono usati per evitare duplicati: se un rifornimento con lo stesso UUID esiste gia, viene ignorato (o aggiornato).

## Formato CSV atteso

```csv
Id,Veicolo,Data,Km,Litri,Costo,Latitudine,Longitudine
a1b2c3d4-...,Fiat Panda,15/03/2026 08:30,125000,45.5,85.00,44.4949,11.3426
```

## Requisiti

1. **Bottone/menu di import**: aggiungere un'opzione nel menu toolbar o nel navigation drawer
2. **File picker**: usare `ACTION_OPEN_DOCUMENT` per selezionare il file CSV dal dispositivo
3. **Parsing CSV**: leggere il file riga per riga, parsare i campi secondo il formato di esportazione
4. **Gestione veicoli**: se il veicolo nel CSV non esiste, crearlo automaticamente
5. **Deduplicazione tramite UUID**: usare l'ID del rifornimento per evitare duplicati
6. **Feedback**: mostrare un Toast/dialog con il risultato dell'importazione (righe importate, skippate, errori)

## Piano di implementazione

### Step 1: Risorse
- Aggiungere item "Importa dati" nel menu toolbar (`menu_main.xml`)
- Aggiungere stringhe necessarie in `strings.xml`

### Step 2: DAO
- Aggiungere metodo `getById(String id)` in `RifornimentoDao` per verificare duplicati
- Aggiungere metodo `getByNome(String nome)` in `VeicoloDao` per cercare veicoli per nome

### Step 3: Logica di importazione in MainActivity
- Registrare `ActivityResultLauncher` per `ACTION_OPEN_DOCUMENT` (MIME: text/csv)
- Metodo `importData()`: apre il file picker
- Callback: legge il file CSV, parsa le righe, gestisce veicoli e deduplicazione su background thread
- Mostra risultato all'utente

### Step 4: Documentazione
- Documentare la feature in `docs/features/import-data.md`

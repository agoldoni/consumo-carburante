# Import Data

## Panoramica

Funzionalita di importazione dei rifornimenti da file CSV. Il formato atteso e lo stesso utilizzato dall'esportazione. Se un veicolo presente nel CSV non esiste nel database, viene creato automaticamente. Gli UUID vengono utilizzati per la deduplicazione: rifornimenti con ID gia presenti vengono ignorati.

## Come funziona

1. L'utente preme l'icona di importazione nella toolbar in alto a destra
2. Si apre il file picker nativo di Android per selezionare un file CSV
3. L'app legge e parsa il file CSV
4. Per ogni riga:
   - Se il veicolo non esiste, viene creato
   - Se l'UUID del rifornimento esiste gia, la riga viene ignorata (deduplicazione)
   - Altrimenti il rifornimento viene inserito
5. Al termine viene mostrato un riepilogo con il numero di righe importate, ignorate e eventuali errori

## Formato CSV atteso

- **Encoding**: UTF-8
- **Separatore**: virgola (`,`)
- **Header**: `Id,Veicolo,Data,Km,Litri,Costo,Latitudine,Longitudine`
- **Date**: formato italiano `dd/MM/yyyy HH:mm`

### Esempio

```csv
Id,Veicolo,Data,Km,Litri,Costo,Latitudine,Longitudine
a1b2c3d4-e5f6-7890-abcd-ef1234567890,Fiat Panda,15/03/2026 08:30,125000,45.5,85.00,44.4949,11.3426
f0e1d2c3-b4a5-6789-0fed-cba987654321,Fiat Panda,10/03/2026 17:15,124500,40.0,76.50,,
```

## Dettagli tecnici

- Il file viene aperto tramite `ACTION_OPEN_DOCUMENT` con MIME type `text/csv`
- Il parsing e l'inserimento avvengono su un thread in background
- La deduplicazione si basa sull'UUID (`id`) del rifornimento
- I veicoli vengono cercati per nome; se non trovati, vengono creati con un nuovo UUID
- Al termine dell'importazione la lista rifornimenti viene ricaricata

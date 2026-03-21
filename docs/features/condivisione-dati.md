# Condivisione Dati

## Panoramica

Funzionalita di esportazione e condivisione dei dati di rifornimento in formato CSV. Permette all'utente di condividere i dati del veicolo selezionato verso qualsiasi piattaforma supportata dal dispositivo (email, WhatsApp, Telegram, Google Drive, ecc.).

## Come funziona

1. L'utente preme l'icona di condivisione (share) nella toolbar in alto a destra
2. L'app genera un file CSV contenente tutti i rifornimenti del veicolo attualmente selezionato
3. Si apre il selettore di piattaforma nativo di Android (ShareSheet)
4. L'utente sceglie la piattaforma verso cui condividere il file

## Formato CSV

- **Encoding**: UTF-8
- **Separatore**: virgola (`,`)
- **Header**: `Id,Veicolo,Data,Km,Litri,Costo,Latitudine,Longitudine`
- **Date**: formato italiano `dd/MM/yyyy HH:mm`
- **Nome file**: `rifornimenti_<nome_veicolo>_<data_export>.csv`

### Esempio

```csv
Id,Veicolo,Data,Km,Litri,Costo,Latitudine,Longitudine
a1b2c3d4-e5f6-7890-abcd-ef1234567890,Fiat Panda,15/03/2026 08:30,125000,45.5,85.00,44.4949,11.3426
f0e1d2c3-b4a5-6789-0fed-cba987654321,Fiat Panda,10/03/2026 17:15,124500,40.0,76.50,,
```

## Dettagli tecnici

- Il file viene scritto nella cache directory dell'app (`getCacheDir()`)
- La condivisione avviene tramite `FileProvider` per garantire la sicurezza
- L'operazione di lettura dal database e scrittura del file avviene su un thread in background
- Se non ci sono rifornimenti da esportare, viene mostrato un messaggio informativo

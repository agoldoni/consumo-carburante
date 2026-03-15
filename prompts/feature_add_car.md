# Feature: Gestione Multi-Veicolo

## Descrizione

Aggiungere il supporto per la gestione di più veicoli nell'app. Ogni rifornimento sarà associato a un veicolo specifico. L'utente potrà creare, modificare ed eliminare veicoli, e filtrare i rifornimenti per veicolo.

## Requisiti

### Menu laterale (Navigation Drawer)
- Aggiungere un menu laterale (hamburger menu) con una voce: **"Le mie auto"**
- La voce apre la schermata di gestione veicoli

### Gestione veicoli
- Schermata dedicata con lista dei veicoli registrati
- Ogni veicolo ha le seguenti proprietà:
  - **Nome** (es. "Fiat Panda")
  - **Targa** (es. "AB123CD")
- Operazioni disponibili:
  - Aggiungere un nuovo veicolo
  - Modificare nome e targa di un veicolo esistente (in qualsiasi momento)
  - Eliminare un veicolo (con conferma, elimina anche i rifornimenti associati)

### Selezione veicolo nella schermata principale
- Aggiungere una combo (Spinner) nella schermata principale per selezionare il veicolo attivo
- La lista dei rifornimenti mostra solo quelli del veicolo selezionato
- La selezione viene salvata e ripristinata al riavvio dell'app

### Associazione rifornimenti
- Ogni nuovo rifornimento viene associato al veicolo attualmente selezionato
- I rifornimenti esistenti (senza veicolo) vengono associati a un veicolo creato automaticamente ("La mia auto")

## Implementazione tecnica

### Database
- Nuova entity Room `Veicolo` (tabella `veicoli`): id (UUID), nome, targa
- Aggiungere campo `veicolo_id` (foreign key) alla tabella `rifornimenti`
- Migrazione DB v1 → v2 con creazione veicolo di default per dati esistenti
- CASCADE delete: eliminare un veicolo elimina tutti i suoi rifornimenti

### UI
- `DrawerLayout` + `NavigationView` + `Toolbar` (sostituisce ActionBar)
- `VeicoloActivity` per gestione CRUD veicoli
- `Spinner` in MainActivity per selezione veicolo
- Dialog per aggiunta/modifica veicolo (nome + targa)

### File coinvolti

#### Nuovi file
- `Veicolo.java` — Room Entity
- `VeicoloDao.java` — DAO con operazioni CRUD
- `VeicoloAdapter.java` — Adapter RecyclerView per lista veicoli
- `VeicoloActivity.java` — Schermata gestione veicoli
- `res/layout/activity_veicolo.xml` — Layout schermata veicoli
- `res/layout/item_veicolo.xml` — Layout card veicolo
- `res/layout/dialog_add_veicolo.xml` — Dialog aggiunta/modifica veicolo
- `res/menu/nav_menu.xml` — Menu Navigation Drawer
- `res/layout/nav_header.xml` — Header Navigation Drawer

#### File modificati
- `Rifornimento.java` — aggiunta campo `veicolo_id` con foreign key
- `RifornimentoDao.java` — aggiunta query filtrata per veicolo
- `AppDatabase.java` — migrazione v1→v2, registrazione Veicolo entity
- `MainActivity.java` — DrawerLayout, Toolbar, Spinner, filtro per veicolo
- `activity_main.xml` — ristrutturazione layout con DrawerLayout
- `strings.xml` — nuove stringhe italiane
- `themes.xml` — passaggio a NoActionBar
- `AndroidManifest.xml` — registrazione VeicoloActivity

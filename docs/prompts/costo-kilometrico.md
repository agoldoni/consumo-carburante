# Feature: Costo Kilometrico

## 1. Obiettivo e motivazione

Permettere all'utente di visualizzare immediatamente il costo in euro per percorrere 100 km, calcolato a partire dai dati di ogni rifornimento. Questo dato, affiancato al consumo km/l già presente, offre una visione economica diretta e facilmente confrontabile tra rifornimenti diversi (anche con prezzi del carburante differenti).

## 2. Scope

### Incluso
- Calcolo al volo del costo per 100 km su ogni card rifornimento
- Visualizzazione sotto la riga "Consumo: X.X km/l" esistente

### Escluso (out of scope)
- Persistenza del valore nel database
- Schermata riepilogativa o statistiche aggregate
- Confronto tra veicoli diversi
- Export del valore nel CSV

## 3. User Stories

1. "Come utente voglio vedere il costo per 100 km su ogni rifornimento per capire quanto mi costa guidare in quel periodo"
2. "Come utente voglio che il costo/100km appaia solo quando il dato è calcolabile (cioè quando esiste anche il consumo km/l) per evitare valori fuorvianti"
3. "Come utente voglio che il formato sia coerente con il resto dell'app (locale italiano, simbolo €) per leggere i dati senza ambiguità"

## 4. Criteri di accettazione

- [ ] Ogni card rifornimento che mostra "Consumo: X.X km/l" mostra anche "Costo: X.XX €/100km" subito sotto
- [ ] La formula è: `(100 / km_per_litro) × (costo / qta_benzina)`
- [ ] Il valore è formattato con 2 decimali, locale italiano (es. "Costo: 8,45 €/100km")
- [ ] Se il consumo km/l non è calcolabile, anche il costo/100km è nascosto
- [ ] Il rifornimento più recente (senza consumo) non mostra il costo/100km
- [ ] Nessuna modifica al database o al modello dati

## 5. Rischi e dipendenze

- **Rischio basso:** La logica dipende dal calcolo km/l esistente in `RifornimentoAdapter`. Se quel calcolo cambia, anche il costo/100km ne risente.
- **Dipendenza:** Il prezzo al litro non è un campo esplicito — va derivato da `costo / qta_benzina` del rifornimento successivo (lo stesso usato per il calcolo km/l).

## 6. Stima effort

| Area | Effort |
|------|--------|
| Logica (Adapter) | 0.5h |
| UI (layout XML + stringa) | 0.5h |
| Test manuale | 0.5h |
| **Totale** | **~1.5h** |

## 7. Milestones

1. Aggiungere `TextView` nel layout `item_rifornimento.xml` sotto `textConsumo`
2. Aggiungere stringa in `strings.xml`
3. Estendere la logica in `RifornimentoAdapter.onBindViewHolder()` per calcolare e mostrare il costo/100km
4. Test manuale su dispositivo

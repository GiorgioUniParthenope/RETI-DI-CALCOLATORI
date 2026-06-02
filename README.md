# Gestione Pub - Progetto Reti di Calcolatori

Applicazione client/server parallela sviluppata in Java per simulare la gestione di un pub.

La traccia considerata e' quella per gruppo da 1 studente: ogni cliente occupa un tavolo distinto. Il sistema e' composto da tre programmi separati:

- `PubServer`: gestisce i tavoli disponibili e prepara gli ordini.
- `WaiterServer`: riceve i clienti, chiede al pub se ci sono posti e inoltra gli ordini.
- `CustomerClient`: simula il cliente che entra, richiede il menu ed effettua un ordine.

## Requisiti

- Java JDK 8 o superiore
- Ambiente Unix-like per compilazione ed esecuzione tramite terminale
- Nessuna libreria esterna

## Compilazione

Con `make`:

```bash
make compile
```

Oppure con `javac`:

```bash
mkdir -p out
javac -encoding UTF-8 -d out $(find src/main/java -name "*.java")
```

## Esecuzione

Aprire tre terminali.

Terminale 1 - avvio del pub:

```bash
java -cp out it.uniparthenope.reti.pub.server.PubServer --port 5000 --tables 5
```

Terminale 2 - avvio del cameriere:

```bash
java -cp out it.uniparthenope.reti.pub.server.WaiterServer --port 6000 --pub-host localhost --pub-port 5000
```

Terminale 3 - client automatico:

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --item PANINO
```

Client interattivo:

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --interactive
```

Comandi disponibili nel client interattivo:

- `enter`
- `menu`
- `order PANINO`
- `exit`

## Porte predefinite

- Pub: `5000`
- Cameriere: `6000`

## Documentazione

La relazione del progetto si trova in [docs/relazione.md](docs/relazione.md).

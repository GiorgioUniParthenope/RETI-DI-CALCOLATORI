# Gestione Pub - Progetto Reti di Calcolatori

Applicazione client/server parallela sviluppata in Java per simulare la gestione di un pub.

La traccia considerata e' quella per gruppo da 1 studente: ogni cliente occupa un tavolo distinto. Il sistema e' composto da tre programmi separati:

- `PubServer`: gestisce i tavoli disponibili e prepara gli ordini.
- `WaiterServer`: riceve i clienti, chiede al pub se ci sono posti, inoltra gli ordini e offre una dashboard cameriere.
- `CustomerClient`: simula il cliente che entra, richiede il menu ed effettua un ordine.
- `CustomerGuiClient`: variante grafica Swing del client cliente.
- `CustomerWebApp`: webapp locale per usare piu' clienti dal browser.

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

## Esecuzione consigliata con webapp

Aprire tre terminali.

Terminale 1 - avvio del pub:

```bash
java -cp out it.uniparthenope.reti.pub.server.PubServer --port 5000 --tables 5 --dashboard-port 7100
```

Terminale 2 - avvio del cameriere:

```bash
java -cp out it.uniparthenope.reti.pub.server.WaiterServer --port 6000 --pub-host localhost --pub-port 5000 --dashboard-port 7200
```

Terminale 3 - avvio della webapp:

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerWebApp --port 7000 --waiter-host localhost --waiter-port 6000 --name Giorgio
```

Poi aprire il browser su:

```text
http://localhost:7000
```

Dashboard lato pub:

```text
http://localhost:7100
```

Dashboard cameriere:

```text
http://localhost:7200
```

Le due pagine web sono responsive e possono essere usate anche da tablet o smartphone.

La webapp clienti permette di far entrare piu' clienti: ogni cliente riceve un tavolo diverso. La dashboard cameriere consente di scegliere un tavolo attivo da una select e di passare al tavolo precedente o successivo con le frecce laterali prima di inviare un ordine. La dashboard pub permette di evadere manualmente gli ordini in preparazione.

Con `make`:

```bash
make web
```

## Esecuzione da terminale

Aprire tre terminali.

Terminale 1 - avvio del pub:

```bash
java -cp out it.uniparthenope.reti.pub.server.PubServer --port 5000 --tables 5 --dashboard-port 7100
```

Terminale 2 - avvio del cameriere:

```bash
java -cp out it.uniparthenope.reti.pub.server.WaiterServer --port 6000 --pub-host localhost --pub-port 5000 --dashboard-port 7200
```

Terminale 3 - client automatico:

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --item PANINO
```

Client interattivo:

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --interactive
```

Client grafico Swing:

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerGuiClient --host localhost --port 6000 --name Giorgio
```

Con `make`:

```bash
make gui
```

Comandi disponibili nel client interattivo:

- `enter`
- `menu`
- `order PANINO`
- `exit`

## Porte predefinite

- Pub: `5000`
- Cameriere: `6000`
- Webapp: `7000`
- Dashboard pub: `7100`
- Dashboard cameriere: `7200`

## Documentazione

La relazione del progetto si trova in [docs/relazione.md](docs/relazione.md).

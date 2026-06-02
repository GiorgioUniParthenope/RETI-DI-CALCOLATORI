# Relazione progetto - Gestione Pub

Studente: Giorgio Cappiello

Matricola: 0124003156

Traccia: Pub

## Descrizione del progetto

Il progetto realizza una applicazione client/server parallela per la gestione semplificata di un pub. La simulazione segue la traccia per gruppo da 1 studente, quindi ogni tavolo puo' ospitare un solo cliente.

Il cliente non comunica direttamente con il pub. La comunicazione passa dal cameriere, che svolge il ruolo di server intermedio:

1. il cliente chiede al cameriere di entrare;
2. il cameriere chiede al pub se esiste un tavolo disponibile;
3. il pub assegna un tavolo oppure rifiuta l'ingresso;
4. il cliente richiede il menu al cameriere;
5. il cliente invia l'ordine al cameriere;
6. il cameriere inoltra l'ordine al pub;
7. il pub simula la preparazione e comunica quando l'ordine e' pronto.

## Architettura

L'architettura e' composta da tre processi Java distinti.

```text
+----------------+        socket TCP        +----------------+        socket TCP        +----------------+
| CustomerClient | <-----------------------> |  WaiterServer  | <-----------------------> |   PubServer    |
+----------------+                           +----------------+                           +----------------+
       client                                      server/client                                server
```

### PubServer

Il `PubServer` e' il server principale del pub. Rimane in ascolto su una porta TCP, accetta connessioni dal cameriere e gestisce:

- assegnazione dei tavoli;
- rifiuto dei clienti quando i tavoli sono pieni;
- preparazione degli ordini con un tempo casuale;
- liberazione del tavolo quando il cliente esce.

Lo stato dei tavoli e' mantenuto nella classe interna `PubState`. I metodi che modificano o leggono lo stato condiviso sono `synchronized`, in modo da evitare condizioni di race quando piu' richieste arrivano contemporaneamente.

### WaiterServer

Il `WaiterServer` rimane in ascolto per i client. Per ogni cliente accettato crea un nuovo thread, quindi piu' clienti possono essere serviti in parallelo.

Il cameriere conserva lo stato della sessione del cliente, in particolare il tavolo assegnato. Quando riceve un ordine valido, apre una connessione verso il pub, invia la richiesta di preparazione e attende la risposta.

### CustomerClient

Il `CustomerClient` simula il comportamento del cliente. Puo' essere eseguito in due modalita':

- automatica, passando il piatto da ordinare con `--item`;
- interattiva, usando i comandi `enter`, `menu`, `order <codice>` ed `exit`.

## Protocollo di comunicazione

La comunicazione usa socket TCP e messaggi testuali terminati da newline. Ogni messaggio ha la forma:

```text
COMANDO chiave=valore chiave2=valore2
```

I valori sono codificati con URL encoding, cosi' possono contenere spazi.

Esempi:

```text
ENTER customer=Giorgio
SEAT_REQUEST customer=Giorgio
SEAT_GRANTED table=1 availableTables=4
MENU items=PANINO%3APanino+con+hamburger%3BPIZZA%3APizza+margherita
ORDER table=1 item=PANINO
ORDER_READY table=1 item=PANINO preparationMs=1800
```

## Parallelismo

Il parallelismo e' ottenuto creando un thread per ogni connessione accettata:

- il pub crea un thread per ogni richiesta proveniente dal cameriere;
- il cameriere crea un thread per ogni cliente connesso.

In questo modo un ordine in preparazione non blocca l'intero sistema. Il thread che ha ricevuto quello specifico ordine rimane in attesa, mentre gli altri client possono continuare a collegarsi ed essere serviti.

## Parti rilevanti del codice

### Accettazione concorrente delle connessioni

Nel `WaiterServer`, ogni client accettato viene gestito da un thread dedicato:

```java
Socket clientSocket = serverSocket.accept();
Thread handler = new Thread(
        () -> handleClient(clientSocket, clientId),
        "waiter-client-" + clientId
);
handler.start();
```

Lo stesso schema e' usato nel `PubServer` per gestire piu' richieste del cameriere.

### Protezione dello stato dei tavoli

Nel `PubServer`, la classe `PubState` usa metodi sincronizzati:

```java
private synchronized Message assignTable(String customerName) {
    if (availableTables.isEmpty()) {
        return Message.builder("SEAT_DENIED")
                .put("reason", "Nessun tavolo disponibile")
                .build();
    }

    int table = availableTables.remove();
    occupiedTables.put(table, customerName);
    return Message.builder("SEAT_GRANTED")
            .put("table", table)
            .put("availableTables", availableTables.size())
            .build();
}
```

Questa scelta impedisce che due clienti ottengano lo stesso tavolo.

### Preparazione dell'ordine

Il pub controlla che il tavolo sia occupato e che il piatto sia presente nel menu, poi simula la preparazione:

```java
int preparationMs = ThreadLocalRandom.current().nextInt(1200, 3501);
Thread.sleep(preparationMs);
return Message.builder("ORDER_READY")
        .put("table", table)
        .put("item", normalizedItem)
        .put("preparationMs", preparationMs)
        .build();
```

## Manuale utente

### Compilazione

```bash
make compile
```

In alternativa:

```bash
mkdir -p out
javac -encoding UTF-8 -d out $(find src/main/java -name "*.java")
```

### Esecuzione del pub

```bash
java -cp out it.uniparthenope.reti.pub.server.PubServer --port 5000 --tables 5
```

Parametri:

- `--port`: porta TCP del server pub;
- `--tables`: numero di tavoli disponibili.

### Esecuzione del cameriere

```bash
java -cp out it.uniparthenope.reti.pub.server.WaiterServer --port 6000 --pub-host localhost --pub-port 5000
```

Parametri:

- `--port`: porta TCP su cui il cameriere accetta i client;
- `--pub-host`: indirizzo del server pub;
- `--pub-port`: porta TCP del server pub.

### Esecuzione del cliente automatico

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --item PANINO
```

### Esecuzione del cliente interattivo

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --interactive
```

Comandi:

- `enter`: chiede al cameriere di entrare;
- `menu`: richiede il menu;
- `order PANINO`: ordina un piatto;
- `exit`: esce dal pub e libera il tavolo.

## Considerazioni finali

Il progetto usa solo socket TCP e classi della libreria standard Java. La separazione tra pub, cameriere e cliente rende esplicito il ruolo di ogni componente e permette di verificare il comportamento parallelo avviando piu' client contemporaneamente.

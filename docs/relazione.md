# Relazione progetto - Gestione Pub

Studente: Giorgio Cappiello

Matricola: 0124003156

Traccia: Pub

## Descrizione del progetto

Il progetto realizza una applicazione client/server parallela per la gestione semplificata di un pub. La simulazione segue la traccia per gruppo da 1 studente, quindi ogni tavolo puo' ospitare un solo cliente.

Oltre al client testuale sono presenti un client grafico Swing e una webapp locale. La webapp e' la modalita' piu' comoda per la dimostrazione: il browser invia richieste HTTP al processo `CustomerWebApp`, che a sua volta comunica con il cameriere tramite socket TCP.

Il `PubServer` espone anche una dashboard HTTP lato server. La dashboard non sostituisce le socket: mostra in modo grafico le operazioni del pub usando ricevute per tavolo.

Il `WaiterServer` espone una dashboard HTTP lato cameriere. Da questa pagina il cameriere vede i tavoli occupati, seleziona il tavolo corrente, puo' cambiare tavolo in qualsiasi momento e invia l'ordinazione scelta al pub.

Le interfacce web sono responsive: il layout si adatta a desktop, tablet e schermi stretti usando griglie fluide e controlli a larghezza piena sui dispositivi mobili.

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
| CustomerGui    |                           |                |                           |                |
+----------------+                           +----------------+                           +----------------+
       client                                      server/client                                server
```

Con la webapp viene aggiunto un piccolo server HTTP locale:

```text
+---------+        HTTP        +----------------+        socket TCP        +----------------+        socket TCP        +-------------+
| Browser | <----------------> | CustomerWebApp | <----------------------> |  WaiterServer  | <---------------------> |  PubServer  |
+---------+                    +----------------+                           +----------------+                         +-------------+
```

Il cameriere e il pub espongono inoltre pagine HTTP di monitoraggio e gestione:

```text
+---------+        HTTP        +-----------------------+
| Browser | <----------------> | Dashboard WaiterServer|
+---------+                    +-----------------------+

+---------+        HTTP        +----------------------+
| Browser | <----------------> | Dashboard PubServer  |
+---------+                    +----------------------+
```

### PubServer

Il `PubServer` e' il server principale del pub. Rimane in ascolto su una porta TCP, accetta connessioni dal cameriere e gestisce:

- assegnazione dei tavoli;
- rifiuto dei clienti quando i tavoli sono pieni;
- preparazione degli ordini con un tempo casuale;
- liberazione del tavolo quando il cliente esce.

Lo stato dei tavoli e' mantenuto nella classe interna `PubState`. I metodi che modificano o leggono lo stato condiviso sono `synchronized`, in modo da evitare condizioni di race quando piu' richieste arrivano contemporaneamente.

La classe interna `PubDashboard` mantiene una ricevuta per ogni tavolo. Quando un cliente entra, la ricevuta indica il tavolo assegnato; quando arriva un ordine, la dashboard mostra prima la preparazione e poi l'ordine pronto; quando il cliente esce, la ricevuta viene chiusa con il tavolo liberato.

Quando un ordine e' in preparazione, la dashboard del pub mostra un pulsante di evasione. Premendo `Evadi ordine`, il server completa manualmente la richiesta senza attendere il termine del tempo casuale.

### WaiterServer

Il `WaiterServer` rimane in ascolto per i client. Per ogni cliente accettato crea un nuovo thread, quindi piu' clienti possono essere serviti in parallelo.

Il cameriere conserva lo stato della sessione del cliente, in particolare il tavolo assegnato. Quando riceve un ordine valido, apre una connessione verso il pub, invia la richiesta di preparazione e attende la risposta.

La dashboard del cameriere usa lo stesso stato delle sessioni attive. Il cameriere puo' selezionare un tavolo dalla lista dei tavoli attivi oppure passare al tavolo precedente o successivo con le frecce laterali, quindi inviare l'ordine per il tavolo scelto.

### CustomerClient

Il `CustomerClient` simula il comportamento del cliente. Puo' essere eseguito in due modalita':

- automatica, passando il piatto da ordinare con `--item`;
- interattiva, usando i comandi `enter`, `menu`, `order <codice>` ed `exit`.

Il `CustomerGuiClient` offre la stessa sequenza operativa tramite interfaccia grafica:

- connessione al cameriere;
- richiesta di ingresso;
- richiesta del menu;
- scelta del piatto da una lista;
- invio dell'ordine;
- uscita dal pub.

Le operazioni di rete della GUI sono eseguite tramite `SwingWorker`, in modo da non bloccare il thread grafico mentre il pub simula la preparazione dell'ordine.

### CustomerWebApp

Il `CustomerWebApp` espone una pagina web locale tramite `HttpServer`, classe inclusa nella libreria standard Java. Non usa framework esterni.

La pagina consente di:

- inserire host, porta e nome cliente;
- far entrare piu' clienti;
- assegnare automaticamente tavoli diversi ai clienti accettati;
- caricare il menu per ogni cliente;
- scegliere un piatto da una lista;
- inviare un ordine per il cliente selezionato;
- visualizzare stato, tavolo assegnato ed esito dell'operazione;
- uscire dal pub liberando il tavolo.

Le API HTTP della webapp traducono ogni azione del browser in un messaggio del protocollo socket verso il cameriere.

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
- `--dashboard-port`: porta HTTP della dashboard lato pub.

Esempio con dashboard:

```bash
java -cp out it.uniparthenope.reti.pub.server.PubServer --port 5000 --tables 5 --dashboard-port 7100
```

Dashboard:

```text
http://localhost:7100
```

### Esecuzione del cameriere

```bash
java -cp out it.uniparthenope.reti.pub.server.WaiterServer --port 6000 --pub-host localhost --pub-port 5000
```

Parametri:

- `--port`: porta TCP su cui il cameriere accetta i client;
- `--pub-host`: indirizzo del server pub;
- `--pub-port`: porta TCP del server pub.
- `--dashboard-port`: porta HTTP della dashboard cameriere.

Esempio con dashboard:

```bash
java -cp out it.uniparthenope.reti.pub.server.WaiterServer --port 6000 --pub-host localhost --pub-port 5000 --dashboard-port 7200
```

Dashboard:

```text
http://localhost:7200
```

### Esecuzione del cliente automatico

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --item PANINO
```

### Esecuzione della webapp

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerWebApp --port 7000 --waiter-host localhost --waiter-port 6000 --name Giorgio
```

In alternativa, dopo la compilazione:

```bash
make web
```

Aprire il browser all'indirizzo:

```text
http://localhost:7000
```

Nella pagina web il cliente usa i pulsanti `Entra`, `Menu`, `Ordina` ed `Esci`.

### Esecuzione del cliente interattivo

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerClient --host localhost --port 6000 --name Giorgio --interactive
```

Comandi:

- `enter`: chiede al cameriere di entrare;
- `menu`: richiede il menu;
- `order PANINO`: ordina un piatto;
- `exit`: esce dal pub e libera il tavolo.

### Esecuzione del client grafico

```bash
java -cp out it.uniparthenope.reti.pub.client.CustomerGuiClient --host localhost --port 6000 --name Giorgio
```

In alternativa, dopo la compilazione:

```bash
make gui
```

Nella finestra grafica il cliente deve:

1. premere `Connetti`;
2. premere `Entra`;
3. premere `Menu`;
4. selezionare un piatto;
5. premere `Ordina`;
6. premere `Esci`.

## Considerazioni finali

Il progetto usa solo socket TCP e classi della libreria standard Java. La separazione tra pub, cameriere e cliente rende esplicito il ruolo di ogni componente e permette di verificare il comportamento parallelo avviando piu' client contemporaneamente.

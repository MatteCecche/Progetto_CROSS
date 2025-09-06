package client;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import server.RegistrazioneRMI;

/**
 * Classe Main del client CROSS (Cryptocurrency Online Stock Simulator)
 * Implementa un client completo che gestisce l'interazione utente con il sistema CROSS
 * seguendo le specifiche del progetto che richiedono separazione tra:
 * - Registrazione via RMI (per studenti vecchio ordinamento)
 * - Login e altre operazioni via TCP (connessioni persistenti)
 *
 * Architettura client:
 * - Interfaccia a linea di comando con prompt interattivo
 * - Gestione configurazione centralizzata tramite file properties
 * - Thread pool per operazioni asincrone (attualmente non utilizzato ma preparato)
 * - Gestione robusta degli errori e cleanup delle risorse
 * - Protocollo di comunicazione JSON conforme all'ALLEGATO 1
 *
 * Responsabilità principali:
 * - Interfaccia utente con parsing comandi e validazione input
 * - Gestione connessioni RMI per registrazione nuovi utenti
 * - Gestione connessioni TCP per login e operazioni post-autenticazione
 * - Serializzazione/deserializzazione messaggi JSON
 * - Interpretazione codici di risposta del server secondo ALLEGATO 1
 */
public class ClientMain {

    // Flag volatile per terminazione thread-safe del client
    // Volatile garantisce visibilità tra thread per shutdown ordinato
    private static volatile boolean running = true;

    // Socket TCP per comunicazione persistente con il server
    // Null quando non connesso, aperto durante sessione autenticata
    private static Socket tcpSocket;

    // Stream per comunicazione JSON bidirezionale con il server
    private static BufferedReader in;   // Lettura risposte JSON dal server
    private static PrintWriter out;     // Invio richieste JSON al server

    // Thread pool per gestire operazioni asincrone future
    // Attualmente non utilizzato ma preparato per estensioni
    private static ExecutorService pool;

    // Scanner per input utente da console con gestione delle righe
    private static Scanner userScanner;

    /**
     * Main del client CROSS - Punto di ingresso dell'applicazione client
     * Orchestratore principale del ciclo di vita del client:
     * 1. Caricamento configurazione da file properties
     * 2. Parsing e validazione parametri di connessione
     * 3. Inizializzazione risorse (thread pool, scanner input)
     * 4. Avvio interfaccia utente interattiva
     * 5. Shutdown ordinato delle risorse
     *
     * Gestione errori robusta con terminazione pulita in caso di problemi critici
     *
     * @param args argomenti da linea di comando (attualmente non utilizzati)
     */
    public static void main(String[] args) {

        System.out.println("[Client] ==== Avvio Client CROSS ====");

        // Caricamento delle configurazioni da file properties
        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("[Client] Impossibile caricare configurazione: " + e.getMessage());
            System.exit(1);
            return; // Per sicurezza del compilatore
        }

        // Parsing dei parametri di configurazione con gestione errori
        String serverHost;
        int tcpPort;
        int rmiPort;
        int socketTimeout;

        try {
            serverHost = parseRequiredStringProperty(config);
            tcpPort = parseRequiredIntProperty(config, "TCP.port");
            rmiPort = parseRequiredIntProperty(config, "RMI.port");
            socketTimeout = parseRequiredIntProperty(config, "socket.timeout");
        } catch (IllegalArgumentException e) {
            System.err.println("[Client] Errore nel parsing dei parametri: " + e.getMessage());
            System.exit(1);
            return; // Per sicurezza del compilatore
        }

        try {
            // Inizializza thread pool per operazioni asincrone future
            pool = Executors.newCachedThreadPool();
            System.out.println("[Client] Thread pool inizializzato (CachedThreadPool)");

            // Inizializza scanner per input utente dalla console
            userScanner = new Scanner(System.in);

            // Avvia loop principale dell'interfaccia utente
            startUserInterface(serverHost, tcpPort, rmiPort, socketTimeout);

        } catch (Exception e) {
            System.err.println("[Client] Errore durante esecuzione: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup garantito delle risorse anche in caso di eccezioni
            shutdownClient();
        }
    }

    /**
     * Carica il file di configurazione del client
     * Legge parametri di connessione dal file client.properties
     * Il client termina se il file non esiste per evitare configurazioni errate
     *
     * Parametri attesi nel file properties:
     * - server.host: indirizzo IP o hostname del server CROSS
     * - TCP.port: porta per connessioni client-server
     * - RMI.port: porta per registry RMI (registrazioni)
     * - socket.timeout: timeout connessioni TCP in millisecondi
     *
     * @return oggetto Properties con le configurazioni caricate e validate
     * @throws IOException se il file non esiste, non è leggibile o ha errori di formato
     */
    private static Properties loadConfiguration() throws IOException {
        File configFile = new File("src/client/client.properties");

        if (!configFile.exists()) {
            throw new IOException("File di configurazione non trovato: " + configFile.getPath());
        }

        Properties prop = new Properties();

        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
            System.out.println("[Client] Configurazione caricata da: " + configFile.getPath());
        } catch (IOException e) {
            System.err.println("[Client] Errore caricamento configurazione: " + e.getMessage());
            throw e; // Rilancia l'eccezione - il client deve terminare
        }

        return prop;
    }

    /**
     * Effettua il parsing di un numero intero da Properties
     * Valida che la property esista e sia un numero intero valido
     * Se la property manca o non è un numero valido, il client termina
     *
     * Garantisce che tutti i parametri numerici siano validi prima dell'avvio
     *
     * @param props oggetto Properties contenente la configurazione
     * @param key chiave da cercare nel file properties
     * @return valore parsato come intero
     * @throws IllegalArgumentException se la property manca o è invalida
     */
    private static int parseRequiredIntProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante nel file client.properties: " + key);
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valore non valido per " + key + ": " + value + " (deve essere un numero intero)");
        }
    }

    /**
     * Effettua il parsing di una stringa da Properties
     * Valida che la property server.host esista e non sia vuota
     * Se la property manca, il client termina
     *
     * Metodo specializzato per il parsing dell'hostname del server
     *
     * @param props oggetto Properties contenente la configurazione
     * @return valore dell'hostname del server con trim applicato
     * @throws IllegalArgumentException se la property manca o è vuota
     */
    private static String parseRequiredStringProperty(Properties props) {
        String value = props.getProperty("server.host");

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante nel file client.properties: " + "server.host");
        }

        return value.trim();
    }

    /**
     * Interfaccia utente principale del client
     * Implementa il loop interattivo con prompt per input comandi utente
     * Gestisce parsing comandi e dispatching alle funzioni appropriate
     *
     * Comandi supportati:
     * - help: mostra lista comandi disponibili
     * - register: registrazione nuovo utente via RMI
     * - login: autenticazione e connessione TCP al server
     * - logout: disconnessione dal server
     * - updateCredentials: aggiornamento password
     * - esci: terminazione client
     *
     * Utilizza prompt ">>" per coerenza con interfaccia server
     *
     * @param serverHost indirizzo del server CROSS
     * @param tcpPort porta TCP del server per connessioni client
     * @param rmiPort porta RMI del server per registrazioni
     * @param socketTimeout timeout per socket TCP in millisecondi
     */
    private static void startUserInterface(String serverHost, int tcpPort, int rmiPort,
                                           int socketTimeout) {

        System.out.println("\n[Client] === CROSS: an exChange oRder bOokS Service ===");
        System.out.println("[Client] Digitare 'help' per lista comandi disponibili");

        while (running) {
            System.out.print(">> ");
            String inputLine = userScanner.nextLine().trim();

            // Ignora righe vuote per migliore UX
            if (inputLine.isEmpty()) {
                continue;
            }

            // Parsing della linea di comando in parti separate da spazi
            String[] parts = inputLine.split("\\s+");
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "help":
                        printHelp();
                        break;

                    case "register":
                        handleRegistration(parts, serverHost, rmiPort);
                        break;

                    case "login":
                        handleLogin(parts, serverHost, tcpPort, socketTimeout);
                        break;

                    case "logout":
                        handleLogout();
                        break;

                    case "updatecredentials":
                        handleUpdateCredentials(parts);
                        break;

                    case "esci":
                        System.out.println("[Client] Chiusura client...");
                        running = false;
                        break;

                    default:
                        System.out.println("[Client] Comando non riconosciuto. Digitare 'help' per la lista dei comandi.");
                        break;
                }

            } catch (Exception e) {
                System.err.println("[Client] Errore esecuzione comando: " + e.getMessage());
            }
        }
    }

    /**
     * Mostra l'help con i comandi disponibili
     * Stampa la lista completa dei comandi supportati con sintassi e descrizione
     * Fornisce reference rapido per l'utente senza dover consultare documentazione
     */
    private static void printHelp() {
        System.out.println("\n=== COMANDI DISPONIBILI ===");
        System.out.println("help                                              - Mostra questo messaggio");
        System.out.println("register <username> <password>                    - Registra nuovo utente");
        System.out.println("login <username> <password>                       - Effettua login");
        System.out.println("logout                                            - Disconnette dal server");
        System.out.println("updateCredentials <username> <old_pwd> <new_pwd>  - Aggiorna password");
        System.out.println("esci                                              - Termina il client");
        System.out.println();
    }

    /**
     * Gestisce la registrazione di un nuovo utente via RMI
     * Implementa il processo di registrazione secondo le specifiche del vecchio ordinamento
     *
     * Flusso di registrazione:
     * 1. Validazione parametri di input (numero e contenuto)
     * 2. Lookup del servizio RMI nel registry del server
     * 3. Chiamata remota al metodo register()
     * 4. Interpretazione codici di risposta secondo ALLEGATO 1
     * 5. Feedback utente con messaggi descrittivi
     *
     * Codici di risposta gestiti (conformi ALLEGATO 1):
     * - 100: OK - registrazione completata
     * - 101: invalid password - password vuota
     * - 102: username not available - username già esistente
     * - 103: other error cases - errori generici
     *
     * @param parts comando parsato dall'input utente
     * @param serverHost indirizzo del server per connessione RMI
     * @param rmiPort porta del registry RMI del server
     */
    private static void handleRegistration(String[] parts, String serverHost, int rmiPort) {
        try {
            // Validazione numero parametri
            if (parts.length != 3) {
                System.out.println("[Client] Uso: register <username> <password>");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            // Validazione contenuto parametri
            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti");
                return;
            }

            // Connessione RMI per registrazione
            Registry registry = LocateRegistry.getRegistry(serverHost, rmiPort);
            RegistrazioneRMI rmiService = (RegistrazioneRMI) registry.lookup("server-rmi");

            // Chiamata remota al servizio di registrazione
            int responseCode = rmiService.register(username, password);

            // Interpretazione e feedback dei codici di risposta secondo ALLEGATO 1
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] Registrazione completata con successo per: " + username);
                    break;
                case 101:
                    System.out.println("[Client] Registrazione fallita: password non valida");
                    break;
                case 102:
                    System.out.println("[Client] Registrazione fallita: username già esistente");
                    break;
                case 103:
                    System.out.println("[Client] Registrazione fallita: errore generico");
                    break;
                default:
                    System.out.println("[Client] Registrazione fallita: codice errore sconosciuto " + responseCode);
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante registrazione: " + e.getMessage());
        }
    }

    /**
     * Gestisce il login e stabilisce connessione TCP persistente con il server
     * Implementa autenticazione e setup sessione secondo il protocollo del progetto
     *
     * Flusso di login:
     * 1. Validazione parametri e controllo stato connessione
     * 2. Stabilimento connessione TCP con timeout configurato
     * 3. Setup stream per comunicazione JSON bidirezionale
     * 4. Costruzione e invio richiesta login JSON conforme ALLEGATO 1
     * 5. Ricezione e parsing risposta server
     * 6. Gestione codici di risposta e mantenimento/chiusura connessione
     *
     * La connessione TCP rimane aperta per tutta la sessione utente
     * fino al logout esplicito o disconnessione
     *
     * @param parts comando parsato dall'input utente
     * @param serverHost indirizzo del server per connessione TCP
     * @param tcpPort porta TCP del server per connessioni client
     * @param socketTimeout timeout per socket TCP in millisecondi
     */
    private static void handleLogin(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        try {
            // Validazione numero parametri
            if (parts.length != 3) {
                System.out.println("[Client] Uso: login <username> <password>");
                return;
            }

            // Controllo stato connessione esistente
            if (tcpSocket != null && tcpSocket.isConnected()) {
                System.out.println("[Client] Già connesso al server. Effettuare logout prima.");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            // Validazione contenuto parametri
            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti");
                return;
            }

            // Stabilisce connessione TCP con timeout configurato
            tcpSocket = new Socket(serverHost, tcpPort);
            tcpSocket.setSoTimeout(socketTimeout);

            // Inizializza stream per comunicazione JSON bidirezionale
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);

            System.out.println("[Client] Connessione TCP stabilita con " +
                    tcpSocket.getRemoteSocketAddress());

            // Costruzione richiesta di login JSON conforme ALLEGATO 1
            JsonObject loginRequest = new JsonObject();
            loginRequest.addProperty("operation", "login");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("password", password);
            loginRequest.add("values", values);

            // Invio richiesta al server
            out.println(loginRequest.toString());

            // Ricezione e parsing risposta JSON del server
            String responseJson = in.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            // Gestione risposta server
            if (responseCode == 100) {
                System.out.println("[Client] Login effettuato con successo");
                // Connessione rimane aperta per la sessione
            } else {
                System.out.println("[Client] Login fallito (codice " + responseCode + "): " + errorMessage);
                // Chiude connessione in caso di login fallito
                tcpSocket.close();
                tcpSocket = null;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante login: " + e.getMessage());
            // Cleanup connessione in caso di errore
            if (tcpSocket != null) {
                try {
                    tcpSocket.close();
                } catch (IOException ex) {
                    // Ignora errori durante cleanup
                }
                tcpSocket = null;
            }
        }
    }

    /**
     * Gestisce il logout e chiude la connessione TCP
     * Implementa disconnessione pulita dal server con notifica server-side
     *
     * Flusso di logout:
     * 1. Controllo stato connessione esistente
     * 2. Invio richiesta logout JSON al server
     * 3. Ricezione conferma dal server
     * 4. Chiusura connessione TCP e cleanup stream
     * 5. Reset stato connessione client
     *
     * La connessione viene chiusa indipendentemente dalla risposta server
     * per garantire cleanup locale anche in caso di problemi di rete
     */
    private static void handleLogout() {
        try {
            // Controllo stato connessione
            if (tcpSocket == null || !tcpSocket.isConnected()) {
                System.out.println("[Client] Non connesso al server");
                return;
            }

            // Costruzione e invio richiesta di logout JSON
            JsonObject logoutRequest = new JsonObject();
            logoutRequest.addProperty("operation", "logout");
            logoutRequest.add("values", new JsonObject()); // values vuoto per logout

            out.println(logoutRequest.toString());

            // Ricezione e parsing risposta del server
            String responseJson = in.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            // Feedback risposta server
            if (responseCode == 100) {
                System.out.println("[Client] Logout effettuato con successo");
            } else {
                System.out.println("[Client] Logout fallito (codice " + responseCode + "): " + errorMessage);
            }

            // Chiude connessione e resetta stato indipendentemente dalla risposta
            tcpSocket.close();
            tcpSocket = null;
            in = null;
            out = null;

        } catch (IOException e) {
            System.err.println("[Client] Errore durante logout: " + e.getMessage());
        }
    }

    /**
     * Gestisce l'aggiornamento delle credenziali utente
     * Implementa cambio password tramite connessione TCP esistente o fallisce se non connesso
     *
     * Secondo l'ALLEGATO 1, questa operazione deve essere disponibile anche quando
     * non si è loggati, ma il server verificherà che l'utente NON sia loggato.
     *
     * Se il client non è connesso, mostrerà un errore appropriato.
     * Se il client è connesso ma l'utente specificato è loggato su un'altra connessione,
     * il server restituirà errore 104.
     *
     * @param parts comando parsato dall'input utente con parametri
     */
    private static void handleUpdateCredentials(String[] parts) {
        // Rimuovo il controllo isConnected() per permettere l'operazione
        // anche quando non si è loggati (conforme ALLEGATO 1)

        if (tcpSocket == null || !tcpSocket.isConnected()) {
            System.out.println("[Client] Connessione TCP necessaria per updateCredentials. Stabilire prima una connessione con login.");
            return;
        }

        try {
            // Validazione numero parametri
            if (parts.length != 4) {
                System.out.println("[Client] Uso: updateCredentials <username> <old_password> <new_password>");
                return;
            }

            String username = parts[1];
            String oldPassword = parts[2];
            String newPassword = parts[3];

            // Validazione contenuto parametri
            if (username.isEmpty() || oldPassword.isEmpty() || newPassword.isEmpty()) {
                System.out.println("[Client] Tutti i parametri sono obbligatori");
                return;
            }

            // Costruzione richiesta JSON conforme ALLEGATO 1
            JsonObject request = new JsonObject();
            request.addProperty("operation", "updateCredentials");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("old_password", oldPassword);
            values.addProperty("new_password", newPassword);
            request.add("values", values);

            // Invio richiesta al server
            out.println(request.toString());

            // Ricezione e parsing risposta del server
            String responseJson = in.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            // Feedback risultato operazione con interpretazione codici ALLEGATO 1
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] Password aggiornata con successo");
                    break;
                case 102:
                    System.out.println("[Client] Errore: username non esistente o password attuale errata");
                    break;
                case 103:
                    System.out.println("[Client] Errore: parametri non validi o nuova password uguale alla precedente");
                    break;
                case 104:
                    System.out.println("[Client] Errore: impossibile cambiare password, utente attualmente loggato");
                    break;
                case 105:
                    System.out.println("[Client] Errore interno del server durante aggiornamento");
                    break;
                default:
                    System.out.println("[Client] Errore aggiornamento password (codice " + responseCode + "): " + errorMessage);
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore aggiornamento credenziali: " + e.getMessage());
        }
    }

    /**
     * Verifica se il client è connesso al server
     * Metodo di utilità per controlli di stato connessione
     * Fornisce feedback utente se operazione richiede connessione attiva
     *
     * Controlla sia l'esistenza del socket che lo stato di connessione
     * per gestire correttamente socket chiusi o non inizializzati
     *
     * @return true se connesso e socket attivo, false altrimenti
     */
    private static boolean isConnected() {
        if (tcpSocket == null || !tcpSocket.isConnected()) {
            System.out.println("[Client] Non connesso al server. Effettuare prima il login.");
            return false;
        }
        return true;
    }

    /**
     * Chiude tutte le risorse del client in modo ordinato
     * Garantisce cleanup completo di tutte le risorse allocate:
     * - Connessione TCP e stream di comunicazione
     * - Thread pool con timeout per terminazione graceful
     * - Scanner input utente
     * - Flag di stato per terminazione thread
     *
     * Questo metodo viene sempre chiamato nel blocco finally del main,
     * garantendo cleanup anche in caso di eccezioni impreviste.
     *
     * Sequenza di shutdown:
     * 1. Impostazione flag terminazione
     * 2. Chiusura connessione TCP se attiva
     * 3. Shutdown graceful thread pool (2 secondi timeout)
     * 4. Chiusura scanner input
     * 5. Logging completamento shutdown
     */
    private static void shutdownClient() {
        System.out.println("[Client] === Shutdown Client CROSS ===");

        running = false;

        // Chiude connessione TCP se attiva
        if (tcpSocket != null && !tcpSocket.isClosed()) {
            try {
                tcpSocket.close();
                System.out.println("[Client] Connessione TCP chiusa");
            } catch (IOException e) {
                System.err.println("[Client] Errore chiusura TCP: " + e.getMessage());
            }
        }

        // Shutdown thread pool con timeout
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    System.out.println("[Client] Thread pool terminato forzatamente");
                } else {
                    System.out.println("[Client] Thread pool terminato correttamente");
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Chiude scanner per input utente
        if (userScanner != null) {
            userScanner.close();
        }

        System.out.println("[Client] Shutdown completato");
    }
}
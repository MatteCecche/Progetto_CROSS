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
 * Classe Main del client CROSS
 * Implementa un client completo che gestisce l'interazione utente con il sistema CROSS
 * - Interfaccia utente con parsing comandi e validazione input
 * - Gestione connessioni RMI per registrazione nuovi utenti
 * - Gestione connessioni TCP per login e operazioni post-autenticazione
 * - Serializzazione/deserializzazione messaggi JSON
 * - Interpretazione codici di risposta del server
 */
public class ClientMain {

    // Flag volatile per terminazione thread-safe del client
    private static volatile boolean running = true;

    // Socket TCP per comunicazione persistente con il server
    private static Socket tcpSocket;

    // Stream per comunicazione JSON bidirezionale con il server
    private static BufferedReader in;   // Lettura risposte JSON dal server
    private static PrintWriter out;     // Invio richieste JSON al server

    // Thread pool per gestire operazioni asincrone future
    private static ExecutorService pool;

    // Scanner per input utente da console con gestione delle righe
    private static Scanner userScanner;

    /**
     * Main del client CROSS
     * 1. Caricamento configurazione da file properties
     * 2. Parsing e validazione parametri di connessione
     * 3. Inizializzazione risorse (thread pool, scanner input)
     * 4. Avvio interfaccia utente interattiva
     * 5. Shutdown ordinato delle risorse
     *
     * @param args argomenti da linea di comando
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
            return;
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
            return;
        }

        try {
            // Inizializza thread pool per le operazioni
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
            shutdownClient();
        }
    }

    /**
     * Carica la configurazione dal file client.properties
     *
     * @return Properties oggetto con la configurazione caricata e validata
     * @throws IOException se il file di configurazione non esiste o è illeggibile
     */
    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();

        File configFile = new File("src/client/client.properties");

        if (!configFile.exists()) {
            throw new IOException("File di configurazione non trovato: " + configFile.getPath());
        }

        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
            System.out.println("[Client] Configurazione caricata da: " + configFile.getPath());
        }

        return prop;
    }

    /**
     * Effettua il parsing di un intero da Properties
     *
     * @param props oggetto Properties contenente la configurazione
     * @param key chiave della property da leggere
     * @return valore della property parsato come intero
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
     *
     * @param serverHost indirizzo del server CROSS
     * @param tcpPort porta TCP del server per connessioni client
     * @param rmiPort porta RMI del server per registrazioni
     * @param socketTimeout timeout per socket TCP in millisecondi
     */
    private static void startUserInterface(String serverHost, int tcpPort, int rmiPort, int socketTimeout) {

        System.out.println("\n[Client] === CROSS: an exChange oRder bOokS Service ===");
        System.out.println("[Client] Digitare 'help' per lista comandi disponibili");

        while (running) {
            System.out.print(">> ");
            String inputLine = userScanner.nextLine().trim();

            // Ignora righe vuote
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
                        handleUpdateCredentials(parts, serverHost, tcpPort, socketTimeout);
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
     */
    private static void printHelp() {
        System.out.println("\n=== COMANDI DISPONIBILI ===");
        System.out.println("help                                               - Mostra questo messaggio");
        System.out.println("register <username> <password>                     - Registra nuovo utente");
        System.out.println("login <username> <password>                        - Effettua login");
        System.out.println("logout                                             - Disconnette dal server");
        System.out.println("updateCredentials <username> <old_pwd> <new_pwd>   - Aggiorna password");
        System.out.println("esci                                               - Termina il client");
        System.out.println();
    }

    /**
     * Gestisce la registrazione di un nuovo utente via RMI
     *
     * @param parts comando parsato dall'input utente
     * @param serverHost indirizzo del server per connessione RMI
     * @param rmiPort porta del registry RMI
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
            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti o contenere solo spazi");
                return;
            }

            // Lookup del servizio RMI nel registry del server
            Registry registry = LocateRegistry.getRegistry(serverHost, rmiPort);
            RegistrazioneRMI stub = (RegistrazioneRMI) registry.lookup("server-rmi");

            // Chiamata remota per registrazione
            int responseCode = stub.register(username, password);

            // Interpretazione codici di risposta
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] Registrazione completata con successo");
                    break;
                case 101:
                    System.out.println("[Client] Errore: password non valida");
                    break;
                case 102:
                    System.out.println("[Client] Errore: username già esistente");
                    break;
                case 103:
                    System.out.println("[Client] Errore durante registrazione");
                    break;
                default:
                    System.out.println("[Client] Codice di risposta non riconosciuto: " + responseCode);
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante registrazione: " + e.getMessage());
        }
    }

    /**
     * Gestisce il login utente con connessione TCP persistente
     * Implementa autenticazione e mantenimento sessione secondo ALLEGATO 1
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
            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti o contenere solo spazi");
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

            // Costruzione richiesta di login JSON
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
     * Gestisce l'aggiornamento delle credenziali utente con connessione temporanea
     * Crea una connessione TCP dedicata solo per questa operazione
     *
     * @param parts comando parsato dall'input utente con parametri
     * @param serverHost indirizzo del server per connessione TCP
     * @param tcpPort porta TCP del server per connessioni client
     * @param socketTimeout timeout per socket TCP in millisecondi
     */
    private static void handleUpdateCredentials(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        // Validazione numero parametri
        if (parts.length != 4) {
            System.out.println("[Client] Uso: updateCredentials <username> <old_password> <new_password>");
            return;
        }

        String username = parts[1];
        String oldPassword = parts[2];
        String newPassword = parts[3];

        // Validazione contenuto parametri
        if (username.trim().isEmpty() || oldPassword.trim().isEmpty() || newPassword.trim().isEmpty()) {
            System.out.println("[Client] Tutti i parametri sono obbligatori e non possono contenere solo spazi");
            return;
        }

        // Variabili per connessione temporanea
        Socket tempSocket = null;
        BufferedReader tempIn = null;
        PrintWriter tempOut = null;

        try {
            System.out.println("[Client] Stabilendo connessione temporanea per updateCredentials...");

            // Crea connessione TCP temporanea dedicata
            tempSocket = new Socket(serverHost, tcpPort);
            tempSocket.setSoTimeout(socketTimeout);

            // Inizializza stream per comunicazione JSON
            tempIn = new BufferedReader(new InputStreamReader(tempSocket.getInputStream()));
            tempOut = new PrintWriter(tempSocket.getOutputStream(), true);

            System.out.println("[Client] Connessione temporanea stabilita con " +
                    tempSocket.getRemoteSocketAddress());

            // Costruzione richiesta JSON
            JsonObject request = new JsonObject();
            request.addProperty("operation", "updateCredentials");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("old_password", oldPassword);
            values.addProperty("new_password", newPassword);
            request.add("values", values);

            // Invio richiesta al server
            tempOut.println(request.toString());

            // Ricezione e parsing risposta del server
            String responseJson = tempIn.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            // Feedback risultato operazione con interpretazione codici
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
            System.err.println("[Client] Errore durante updateCredentials: " + e.getMessage());
        } finally {
            // Cleanup garantito della connessione temporanea
            try {
                if (tempOut != null) {
                    tempOut.close();
                }
                if (tempIn != null) {
                    tempIn.close();
                }
                if (tempSocket != null && !tempSocket.isClosed()) {
                    tempSocket.close();
                    System.out.println("[Client] Connessione temporanea chiusa");
                }
            } catch (IOException e) {
                // Ignora errori durante cleanup - connessione temporanea
                System.err.println("[Client] Avviso: errore durante chiusura connessione temporanea: " + e.getMessage());
            }
        }
    }

    /**
     * Verifica se il client è connesso al server
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
     * Garantisce cleanup completo di tutte le risorse allocate
     */
    private static void shutdownClient() {
        System.out.println("[Client] Avvio procedura di shutdown...");

        // Imposta flag di terminazione per tutti i thread
        running = false;

        // Chiusura connessione TCP se attiva
        try {
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
                System.out.println("[Client] Connessione TCP chiusa");
            }
        } catch (IOException e) {
            System.err.println("[Client] Errore chiusura connessione TCP: " + e.getMessage());
        }

        // Shutdown del thread pool
        if (pool != null && !pool.isShutdown()) {
            try {
                pool.shutdown();
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

        // Chiusura scanner input
        if (userScanner != null) {
            userScanner.close();
            System.out.println("[Client] Scanner input chiuso");
        }

        System.out.println("[Client] Shutdown completato");
    }
}
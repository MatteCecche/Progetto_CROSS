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
 * Implementa un client che gestisce:
 * - Registrazione via RMI
 * - Login e operazioni via TCP
 */
public class ClientMain {

    // Flag volatile per terminazione thread-safe del client
    private static volatile boolean running = true;

    // Socket TCP per comunicazione con il server
    private static Socket tcpSocket;

    // Stream per comunicazione JSON con il server
    private static BufferedReader in;
    private static PrintWriter out;

    // Thread pool per gestire operazioni asincrone
    private static ExecutorService pool;

    // Scanner per input utente
    private static Scanner userScanner;

    public static void main(String[] args) {

        System.out.println("[Client] === Avvio Client CROSS ===");

        // Caricamento delle configurazioni da file properties
        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("[Client] Impossibile caricare configurazione: " + e.getMessage());
            System.exit(1);
            return; // Per il compilatore
        }

        // Parsing dei parametri di configurazione
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
            return; // Per il compilatore
        }

        try {
            // Inizializza thread pool per operazioni asincrone
            pool = Executors.newCachedThreadPool();
            System.out.println("[Client] Thread pool inizializzato (CachedThreadPool)");

            // Inizializza scanner per input utente
            userScanner = new Scanner(System.in);

            // Loop principale dell'interfaccia utente
            startUserInterface(serverHost, tcpPort, rmiPort, socketTimeout);

        } catch (Exception e) {
            System.err.println("[Client] Errore durante esecuzione: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdownClient();
        }
    }

    /**
     * Carica il file di configurazione del client
     *
     * @return oggetto Properties con le configurazioni
     * @throws IOException se il file non esiste o errori di lettura
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
     * Effettua il parsing di un numero intero
     * Se la property manca o non è un numero valido, il client termina
     *
     * @param props oggetto Properties
     * @param key chiave da cercare
     * @return valore parsato
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
     * Effettua il parsing di una stringa
     * Se la property manca, il client termina
     *
     * @param props oggetto Properties
     * @return valore parsato
     * @throws IllegalArgumentException se la property manca
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
     * Gestisce registrazione, login e operazioni
     *
     * @param serverHost indirizzo del server
     * @param tcpPort porta TCP del server
     * @param rmiPort porta RMI del server
     * @param socketTimeout timeout per socket TCP
     */
    private static void startUserInterface(String serverHost, int tcpPort, int rmiPort,
                                           int socketTimeout) {

        System.out.println("\n[Client] === CROSS: an exChange oRder bOokS Service ===");
        System.out.println("[Client] Digitare 'help' per lista comandi disponibili");

        while (running) {
            System.out.print(">> ");
            String inputLine = userScanner.nextLine().trim();

            if (inputLine.isEmpty()) {
                continue;
            }

            // Parsing della linea di comando in parti
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
                        System.out.println("[Client] Comando non riconosciuto. Digitare 'help' per aiuto.");
                        break;
                }

            } catch (Exception e) {
                System.err.println("[Client] Errore esecuzione comando: " + e.getMessage());
            }
        }
    }

    /**
     * Mostra l'help con i comandi disponibili
     */
    private static void printHelp() {
        System.out.println("\n=== COMANDI DISPONIBILI ===");
        System.out.println("help                            - Mostra questo messaggio");
        System.out.println("register <username> <password>  - Registra nuovo utente via RMI");
        System.out.println("login <username> <password>     - Effettua login e connessione al server");
        System.out.println("logout                          - Disconnette dal server");
        System.out.println("updateCredentials <username> <old_pwd> <new_pwd> - Aggiorna password");
        System.out.println("esci                            - Termina il client");
        System.out.println();
    }

    /**
     * Gestisce la registrazione di un nuovo utente via RMI
     *
     * @param parts comando parsato
     * @param serverHost indirizzo del server
     * @param rmiPort porta RMI del server
     */
    private static void handleRegistration(String[] parts, String serverHost, int rmiPort) {
        try {
            if (parts.length != 3) {
                System.out.println("[Client] Uso: register <username> <password>");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti");
                return;
            }

            // Connessione RMI per registrazione (vecchio ordinamento)
            Registry registry = LocateRegistry.getRegistry(serverHost, rmiPort);
            RegistrazioneRMI rmiService = (RegistrazioneRMI) registry.lookup("server-rmi");

            int responseCode = rmiService.register(username, password);

            // Gestisce i codici di risposta secondo le specifiche del progetto
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
     * Gestisce il login e stabilisce connessione TCP con il server
     *
     * @param parts comando parsato
     * @param serverHost indirizzo del server
     * @param tcpPort porta TCP del server
     * @param socketTimeout timeout per socket TCP
     */
    private static void handleLogin(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        try {
            if (parts.length != 3) {
                System.out.println("[Client] Uso: login <username> <password>");
                return;
            }

            if (tcpSocket != null && tcpSocket.isConnected()) {
                System.out.println("[Client] Già connesso al server. Effettuare logout prima.");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            if (username.isEmpty() || password.isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti");
                return;
            }

            // Stabilisce connessione TCP
            tcpSocket = new Socket(serverHost, tcpPort);
            tcpSocket.setSoTimeout(socketTimeout);

            // Inizializza stream per comunicazione JSON
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);

            System.out.println("[Client] Connessione TCP stabilita con " +
                    tcpSocket.getRemoteSocketAddress());

            // Invia richiesta di login con formato JSON
            JsonObject loginRequest = new JsonObject();
            loginRequest.addProperty("operation", "login");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("password", password);
            loginRequest.add("values", values);

            out.println(loginRequest.toString());

            // Legge risposta del server
            String responseJson = in.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            if (responseCode == 100) {
                System.out.println("[Client] Login effettuato con successo");

            } else {
                System.out.println("[Client] Login fallito (codice " + responseCode + "): " + errorMessage);
                tcpSocket.close();
                tcpSocket = null;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante login: " + e.getMessage());
            if (tcpSocket != null) {
                try {
                    tcpSocket.close();
                } catch (IOException ex) {
                    // Ignora
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
            if (tcpSocket == null || !tcpSocket.isConnected()) {
                System.out.println("[Client] Non connesso al server");
                return;
            }

            // Invia richiesta di logout con formato JSON
            JsonObject logoutRequest = new JsonObject();
            logoutRequest.addProperty("operation", "logout");
            logoutRequest.add("values", new JsonObject()); // values vuoto per logout

            out.println(logoutRequest.toString());

            // Legge risposta del server
            String responseJson = in.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            if (responseCode == 100) {
                System.out.println("[Client] Logout effettuato con successo");
            } else {
                System.out.println("[Client] Logout fallito (codice " + responseCode + "): " + errorMessage);
            }

            // Chiude connessione comunque
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
     *
     * @param parts comando parsato
     */
    private static void handleUpdateCredentials(String[] parts) {
        if (!isConnected()) return;

        try {
            if (parts.length != 4) {
                System.out.println("[Client] Uso: updateCredentials <username> <old_password> <new_password>");
                return;
            }

            String username = parts[1];
            String oldPassword = parts[2];
            String newPassword = parts[3];

            if (username.isEmpty() || oldPassword.isEmpty() || newPassword.isEmpty()) {
                System.out.println("[Client] Tutti i parametri sono obbligatori");
                return;
            }

            // Crea richiesta JSON
            JsonObject request = new JsonObject();
            request.addProperty("operation", "updateCredentials");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("old_password", oldPassword);
            values.addProperty("new_password", newPassword);
            request.add("values", values);

            out.println(request.toString());

            // Legge risposta del server
            String responseJson = in.readLine();
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();

            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            if (responseCode == 100) {
                System.out.println("[Client] Password aggiornata con successo");
            } else {
                System.out.println("[Client] Errore aggiornamento password (codice " + responseCode + "): " + errorMessage);
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore aggiornamento credenziali: " + e.getMessage());
        }
    }

    /**
     * Verifica se il client è connesso al server
     */
    private static boolean isConnected() {
        if (tcpSocket == null || !tcpSocket.isConnected()) {
            System.out.println("[Client] Non connesso al server. Effettuare prima il login.");
            return false;
        }
        return true;
    }


    /**
     * Chiude tutte le risorse del client
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

        // Shutdown thread pool
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

        // Chiude scanner
        if (userScanner != null) {
            userScanner.close();
        }

        System.out.println("[Client] Shutdown completato");
    }
}
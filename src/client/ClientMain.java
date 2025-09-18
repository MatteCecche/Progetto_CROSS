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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import server.RegistrazioneRMI;

/**
 * Classe Main del client CROSS
 * Implementa un client completo che gestisce l'interazione utente con il sistema CROSS
 * - Interfaccia utente con parsing comandi e validazione input
 * - Gestione connessioni RMI per registrazione nuovi utenti
 * - Gestione connessioni TCP per login e operazioni post-autenticazione
 * - Listener multicast per notifiche prezzo (vecchio ordinamento)
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

    // === VARIABILI PER GESTIONE MULTICAST (VECCHIO ORDINAMENTO) ===

    // Listener multicast per notifiche prezzo
    private static MulticastListener multicastListener;

    // Thread per listener multicast
    private static Thread multicastThread;

    // Username dell'utente attualmente loggato
    private static String currentLoggedUsername;

    // Parametri multicast letti da properties
    private static String multicastAddress;
    private static int multicastPort;

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
            serverHost = parseRequiredStringProperty(config, "server.host");
            tcpPort = parseRequiredIntProperty(config, "TCP.port");
            rmiPort = parseRequiredIntProperty(config, "RMI.port");
            socketTimeout = parseRequiredIntProperty(config, "socket.timeout");

            // Lettura parametri multicast da properties (vecchio ordinamento)
            multicastAddress = parseRequiredStringProperty(config, "multicast.address");
            multicastPort = parseRequiredIntProperty(config, "multicast.port");

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

            // Avvia loop principale dell'interfaccia utente con parametri multicast
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
     * @param key chiave della property da leggere
     * @return valore dell'hostname del server con trim applicato
     * @throws IllegalArgumentException se la property manca o è vuota
     */
    private static String parseRequiredStringProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante nel file client.properties: " + key);
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
        System.out.println("[Client] Configurazione multicast: " + multicastAddress + ":" + multicastPort);

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

                    // === COMANDI TRADING ===
                    case "insertlimitorder":
                        handleInsertLimitOrder(parts);
                        break;
                    case "insertmarketorder":
                        handleInsertMarketOrder(parts);
                        break;
                    case "insertstoporder":
                        handleInsertStopOrder(parts);
                        break;
                    case "cancelorder":
                        handleCancelOrder(parts);
                        break;
                    case "marketprice":
                        handleMarketPrice();
                        break;
                    case "getpricehistory":
                        handleGetPriceHistory(parts);
                        break;

                    // === NOTIFICHE PREZZO (VECCHIO ORDINAMENTO) ===
                    case "registerpricealert":
                        handleRegisterPriceAlert(parts);
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
        System.out.println("register <username> <password>                    - Registra nuovo utente");
        System.out.println("login <username> <password>                       - Effettua login");
        System.out.println("logout                                             - Disconnette dal server");
        System.out.println("updateCredentials <username> <old_pwd> <new_pwd>  - Aggiorna password");
        System.out.println();
        System.out.println("=== COMANDI TRADING (richiede login) ===");
        System.out.println("insertLimitOrder <bid/ask> <size> <price>          - Inserisce ordine limite");
        System.out.println("insertMarketOrder <bid/ask> <size>                 - Inserisce ordine a mercato");
        System.out.println("insertStopOrder <bid/ask> <size> <stopPrice>       - Inserisce stop order");
        System.out.println("cancelOrder <orderId>                              - Cancella ordine");
        System.out.println("marketPrice                                        - Mostra prezzo corrente BTC");
        System.out.println("getPriceHistory <MMYYYY>                           - Ottieni storico prezzi mensile");
        System.out.println();
        System.out.println("=== NOTIFICHE PREZZO (vecchio ordinamento) ===");
        System.out.println("registerPriceAlert <soglia>                        - Registra notifica soglia prezzo");
        System.out.println("                                                     Esempio: registerPriceAlert 65000000");
        System.out.println("                                                     (per ricevere notifica quando BTC > 65.000 USD)");
        System.out.println();
        System.out.println("NOTA: size e price in millesimi (es: 1000 = 1 BTC, 58000000 = 58.000 USD)");
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
                    System.out.println("[Client] Registrazione completata con successo!");
                    break;
                case 101:
                    System.out.println("[Client] Errore: password non valida (troppo corta o vuota)");
                    break;
                case 102:
                    System.out.println("[Client] Errore: username già in uso, scegliere un altro nome utente");
                    break;
                case 103:
                    System.out.println("[Client] Errore interno del server durante registrazione");
                    break;
                default:
                    System.out.println("[Client] Errore registrazione sconosciuto (codice " + responseCode + ")");
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante registrazione RMI: " + e.getMessage());
            System.err.println("[Client] Verificare che il server sia avviato e raggiungibile");
        }
    }

    /**
     * Gestisce il login dell'utente e avvia listener multicast per notifiche prezzo
     * Estende funzionalità esistente con supporto multicast (vecchio ordinamento)
     *
     * @param parts comando parsato dall'input utente
     * @param serverHost indirizzo del server per connessione TCP
     * @param tcpPort porta TCP del server
     * @param socketTimeout timeout per la connessione
     */
    private static void handleLogin(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        try {
            // Validazione parametri
            if (parts.length != 3) {
                System.out.println("[Client] Uso: login <username> <password>");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                System.out.println("[Client] Username e password non possono essere vuoti");
                return;
            }

            // Controlla se già loggato
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                System.out.println("[Client] Utente già loggato. Effettuare logout prima di un nuovo login.");
                return;
            }

            // Stabilisce connessione TCP con il server
            tcpSocket = new Socket(serverHost, tcpPort);
            tcpSocket.setSoTimeout(socketTimeout);

            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);

            // Creazione richiesta JSON di login
            JsonObject request = new JsonObject();
            request.addProperty("operation", "login");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("password", password);
            request.add("values", values);

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa durante login");
                return;
            }

            // Parsing risposta JSON
            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.get("errorMessage").getAsString();

            // Gestione risultato login
            boolean loginSuccessful = false;
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] Login effettuato con successo!");
                    System.out.println("[Client] Connesso come: " + username);
                    currentLoggedUsername = username;
                    loginSuccessful = true;
                    break;
                case 101:
                    System.out.println("[Client] Errore login: credenziali non valide");
                    break;
                case 102:
                    System.out.println("[Client] Errore login: utente già connesso");
                    break;
                case 103:
                    System.out.println("[Client] Errore interno del server durante login");
                    break;
                default:
                    System.out.println("[Client] Errore login (codice " + responseCode + "): " + errorMessage);
                    break;
            }

            // Se login fallito, chiudi connessione
            if (!loginSuccessful) {
                try {
                    if (out != null) out.close();
                    if (in != null) in.close();
                    if (tcpSocket != null) tcpSocket.close();
                    tcpSocket = null;
                    in = null;
                    out = null;
                } catch (IOException e) {
                    System.err.println("[Client] Errore chiusura connessione dopo login fallito: " + e.getMessage());
                }
                return;
            }

            // Avvia listener multicast dopo login successful
            startMulticastListener();

        } catch (Exception e) {
            System.err.println("[Client] Errore durante login: " + e.getMessage());
        }
    }

    /**
     * Gestisce il logout dell'utente e ferma listener multicast
     */
    private static void handleLogout() {
        try {
            // Controlla se effettivamente loggato
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Nessun utente attualmente loggato");
                return;
            }

            // Ferma listener multicast prima del logout
            stopMulticastListener();

            // Creazione richiesta JSON di logout
            JsonObject request = new JsonObject();
            request.addProperty("operation", "logout");
            request.add("values", new JsonObject());

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr != null) {
                JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
                int responseCode = response.get("response").getAsInt();

                switch (responseCode) {
                    case 100:
                        System.out.println("[Client] Logout effettuato con successo");
                        break;
                    default:
                        System.out.println("[Client] Logout completato (codice " + responseCode + ")");
                        break;
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante logout: " + e.getMessage());
        } finally {
            // Cleanup connessione TCP
            try {
                if (out != null) out.close();
                if (in != null) in.close();
                if (tcpSocket != null) tcpSocket.close();
            } catch (IOException e) {
                System.err.println("[Client] Errore chiusura connessione: " + e.getMessage());
            }

            tcpSocket = null;
            in = null;
            out = null;
            currentLoggedUsername = null;

            System.out.println("[Client] Disconnesso dal server");
        }
    }

    /**
     * Gestisce comando per registrare notifiche soglia prezzo
     * Sintassi: registerPriceAlert <soglia_prezzo>
     *
     * @param parts comando parsato dall'input utente
     */
    private static void handleRegisterPriceAlert(String[] parts) {
        try {
            // Controllo login
            if (tcpSocket == null || tcpSocket.isClosed() || currentLoggedUsername == null) {
                System.out.println("[Client] Errore: effettuare login prima di registrare notifiche prezzo");
                return;
            }

            // Validazione parametri
            if (parts.length != 2) {
                System.out.println("[Client] Uso: registerPriceAlert <soglia_prezzo>");
                System.out.println("[Client] Esempio: registerPriceAlert 65000000  (per 65.000 USD)");
                return;
            }

            // Parsing soglia prezzo
            int thresholdPrice;
            try {
                thresholdPrice = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                System.out.println("[Client] Errore: soglia_prezzo deve essere un numero intero in millesimi");
                System.out.println("[Client] Esempio: 65000000 per 65.000 USD");
                return;
            }

            if (thresholdPrice <= 0) {
                System.out.println("[Client] Errore: soglia_prezzo deve essere maggiore di zero");
                return;
            }

            // Creazione richiesta JSON
            JsonObject request = new JsonObject();
            request.addProperty("operation", "registerPriceAlert");

            JsonObject values = new JsonObject();
            values.addProperty("thresholdPrice", thresholdPrice);
            request.add("values", values);

            // Invio richiesta al server
            out.println(request.toString());
            String responseStr = in.readLine();

            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            // Parsing risposta
            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();

            // Gestione risultato
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] ✅ Notifiche prezzo registrate con successo!");
                    System.out.println("[Client] Soglia: " + formatPrice(thresholdPrice) + " USD");
                    System.out.println("[Client] Riceverai notifiche multicast quando il prezzo BTC supererà questa soglia");

                    // Mostra info multicast se presente
                    if (response.has("multicastInfo")) {
                        JsonObject multicastInfo = response.getAsJsonObject("multicastInfo");
                        System.out.println("[Client] Multicast attivo: " +
                                multicastInfo.get("multicastAddress").getAsString() + ":" +
                                multicastInfo.get("multicastPort").getAsInt());
                    }
                    break;

                case 101:
                    System.out.println("[Client] Errore: utente non loggato");
                    break;

                case 103:
                    String errorMsg = response.has("message") ?
                            response.get("message").getAsString() : "Parametri non validi";
                    System.out.println("[Client] Errore: " + errorMsg);
                    break;

                default:
                    System.out.println("[Client] Errore registrazione notifiche (codice " + responseCode + ")");
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante registerPriceAlert: " + e.getMessage());
        }
    }

    // === METODI PER GESTIONE MULTICAST LISTENER ===

    /**
     * Avvia il listener multicast per ricevere notifiche prezzo dal server
     */
    private static void startMulticastListener() {
        try {
            if (multicastListener == null && currentLoggedUsername != null) {

                // Crea e avvia listener con parametri da properties
                multicastListener = new MulticastListener(multicastAddress, multicastPort, currentLoggedUsername);
                multicastListener.start();

                // Avvia thread separato per listener
                multicastThread = new Thread(multicastListener, "MulticastListener-" + currentLoggedUsername);
                multicastThread.setDaemon(true); // Thread daemon per non bloccare shutdown
                multicastThread.start();

                System.out.println("[Client] Listener multicast avviato per notifiche prezzo");
            }
        } catch (Exception e) {
            System.err.println("[Client] Errore avvio listener multicast: " + e.getMessage());
        }
    }

    /**
     * Ferma il listener multicast
     */
    private static void stopMulticastListener() {
        try {
            if (multicastListener != null) {
                multicastListener.stop();

                if (multicastThread != null && multicastThread.isAlive()) {
                    multicastThread.interrupt();

                    // Aspetta terminazione thread per max 2 secondi
                    try {
                        multicastThread.join(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                multicastListener = null;
                multicastThread = null;

                System.out.println("[Client] Listener multicast fermato");
            }
        } catch (Exception e) {
            System.err.println("[Client] Errore durante stop listener multicast: " + e.getMessage());
        }
    }

    // === METODI ESISTENTI TRADING (DA IMPLEMENTARE) ===

    private static void handleUpdateCredentials(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        // TODO: Implementazione esistente
        System.out.println("[Client] Comando updateCredentials non ancora implementato");
    }

    private static void handleInsertLimitOrder(String[] parts) {
        // TODO: Implementazione esistente
        System.out.println("[Client] Comando insertLimitOrder non ancora implementato");
    }

    private static void handleInsertMarketOrder(String[] parts) {
        // TODO: Implementazione esistente
        System.out.println("[Client] Comando insertMarketOrder non ancora implementato");
    }

    private static void handleInsertStopOrder(String[] parts) {
        // TODO: Implementazione esistente
        System.out.println("[Client] Comando insertStopOrder non ancora implementato");
    }

    private static void handleCancelOrder(String[] parts) {
        // TODO: Implementazione esistente
        System.out.println("[Client] Comando cancelOrder non ancora implementato");
    }

    private static void handleMarketPrice() {
        // TODO: Implementazione esistente
        System.out.println("[Client] Comando marketPrice non ancora implementato");
    }

    /**
     * Gestisce comando getPriceHistory dell'utente
     * Formato comando: getPriceHistory <MMYYYY>
     *
     * @param parts comando parsato dall'input utente
     */
    private static void handleGetPriceHistory(String[] parts) {
        try {
            // Validazione parametri
            if (parts.length != 2) {
                System.out.println("[Client] Uso: getPriceHistory <MMYYYY>");
                System.out.println("[Client] Esempio: getPriceHistory 012025 (per gennaio 2025)");
                return;
            }

            String month = parts[1];

            // Validazione formato mese
            if (month.length() != 6) {
                System.out.println("[Client] Formato mese non valido. Usare MMYYYY (es: 012025)");
                return;
            }

            try {
                int monthInt = Integer.parseInt(month.substring(0, 2));
                int yearInt = Integer.parseInt(month.substring(2, 6));

                if (monthInt < 1 || monthInt > 12 || yearInt < 2020 || yearInt > 2030) {
                    System.out.println("[Client] Mese o anno non valido. Mese: 01-12, Anno: 2020-2030");
                    return;
                }
            } catch (NumberFormatException e) {
                System.out.println("[Client] Formato mese non numerico. Usare MMYYYY (es: 012025)");
                return;
            }

            // Controllo connessione TCP
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Errore: non connesso al server. Effettuare login prima.");
                return;
            }

            // Costruzione richiesta JSON secondo ALLEGATO 1
            JsonObject request = new JsonObject();
            request.addProperty("operation", "getPriceHistory");

            JsonObject values = new JsonObject();
            values.addProperty("month", month);
            request.add("values", values);

            // Invio richiesta al server
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            // Parsing risposta JSON
            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            // Gestione risposta
            if (response.has("error")) {
                System.out.println("[Client] Errore: " + response.get("error").getAsString());
                return;
            }

            if (!response.has("priceHistory")) {
                System.out.println("[Client] Errore: risposta server non valida");
                return;
            }

            // Estrazione dati
            String responseMonth = response.get("month").getAsString();
            int totalDays = response.get("totalDays").getAsInt();
            JsonArray historyData = response.getAsJsonArray("priceHistory");

            // Display risultati
            displayPriceHistory(responseMonth, totalDays, historyData);

        } catch (Exception e) {
            System.err.println("[Client] Errore durante getPriceHistory: " + e.getMessage());
        }
    }

    /**
     * Visualizza i dati storici dei prezzi in formato tabellare user-friendly
     * Mostra OHLC (Open, High, Low, Close) + Volume per ogni giorno
     *
     * @param month mese richiesto in formato MMYYYY
     * @param totalDays numero totale di giorni con dati
     * @param historyData array JSON contenente i dati storici
     */
    private static void displayPriceHistory(String month, int totalDays, JsonArray historyData) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📈 STORICO PREZZI BTC - " + formatMonthYear(month));
        System.out.println("=".repeat(80));

        if (totalDays == 0) {
            System.out.println("Nessun dato disponibile per il mese richiesto.");
            System.out.println("Potrebbe essere necessario eseguire alcune operazioni di trading prima.");
            System.out.println("=".repeat(80));
            return;
        }

        System.out.println("Giorni con dati: " + totalDays);
        System.out.println();

        // Header tabella
        System.out.printf("%-12s %-10s %-10s %-10s %-10s %-12s %-8s%n",
                "Data", "Apertura", "Massimo", "Minimo", "Chiusura", "Volume", "Trade");
        System.out.println("-".repeat(80));

        // Dati per ogni giorno
        for (int i = 0; i < historyData.size(); i++) {
            JsonObject dayData = historyData.get(i).getAsJsonObject();

            String date = dayData.get("date").getAsString();
            int openPrice = dayData.get("openPrice").getAsInt();
            int highPrice = dayData.get("highPrice").getAsInt();
            int lowPrice = dayData.get("lowPrice").getAsInt();
            int closePrice = dayData.get("closePrice").getAsInt();
            int volume = dayData.get("volume").getAsInt();
            int tradesCount = dayData.get("tradesCount").getAsInt();

            // Formatta e mostra riga
            System.out.printf("%-12s %-10s %-10s %-10s %-10s %-12s %-8d%n",
                    formatDate(date),
                    formatPrice(openPrice),
                    formatPrice(highPrice),
                    formatPrice(lowPrice),
                    formatPrice(closePrice),
                    formatVolume(volume),
                    tradesCount);
        }

        System.out.println("=".repeat(80));
        System.out.println("💡 Prezzi in USD, Volume in BTC");
    }

    /**
     * Formatta mese da MMYYYY a formato leggibile
     *
     * @param monthYear formato MMYYYY (es: "012025")
     * @return formato leggibile (es: "Gennaio 2025")
     */
    private static String formatMonthYear(String monthYear) {
        try {
            int month = Integer.parseInt(monthYear.substring(0, 2));
            String year = monthYear.substring(2, 6);

            String[] mesi = {
                    "", "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
                    "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
            };

            return mesi[month] + " " + year;
        } catch (Exception e) {
            return monthYear; // Fallback al formato originale
        }
    }

    /**
     * Formatta data da YYYY-MM-DD a formato leggibile
     *
     * @param dateStr data in formato ISO (es: "2025-01-15")
     * @return data formattata (es: "15/01/2025")
     */
    private static String formatDate(String dateStr) {
        try {
            String[] parts = dateStr.split("-");
            return parts[2] + "/" + parts[1] + "/" + parts[0];
        } catch (Exception e) {
            return dateStr; // Fallback al formato originale
        }
    }

    /**
     * Formatta volume in formato leggibile
     *
     * @param volumeInMillis volume in millesimi di BTC
     * @return volume formattato (es: "1.250 BTC")
     */
    private static String formatVolume(int volumeInMillis) {
        return String.format("%.3f BTC", volumeInMillis / 1000.0);
    }

    // === UTILITY METHODS ===

    /**
     * Formatta prezzo in millesimi per display user-friendly
     *
     * @param priceInMillis prezzo in millesimi USD
     * @return prezzo formattato (es: "58.000")
     */
    private static String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Esegue lo shutdown ordinato del client
     * Ferma listener multicast, chiude connessioni TCP, libera risorse
     */
    private static void shutdownClient() {
        System.out.println("[Client] Iniziando shutdown del client...");

        running = false;

        // Ferma listener multicast
        stopMulticastListener();

        // Chiusura connessioni TCP
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[Client] Errore chiusura connessioni: " + e.getMessage());
        }

        // Chiusura thread pool
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Chiusura scanner
        if (userScanner != null) {
            userScanner.close();
        }

        System.out.println("[Client] Shutdown completato");
    }
}
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
import common.RegistrazioneRMI;

/**
 * Classe Main del client CROSS
 * Implementa un client completo che gestisce l'interazione utente con il sistema CROSS
 * - Interfaccia utente con parsing comandi e validazione input
 * - Gestione connessioni RMI per registrazione nuovi utenti
 * - Gestione connessioni TCP per login e operazioni post-autenticazione
 * - Listener multicast per notifiche prezzo
 * - Listener UDP per notifiche trade (con supporto porta automatica)
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

    // Listener multicast per notifiche prezzo
    private static MulticastListener multicastListener;

    // Thread per listener multicast
    private static Thread multicastThread;

    // Listener UDP per notifiche trade
    private static UDPNotificationListener udpListener;
    private static Thread udpListenerThread;
    private static int udpNotificationPort;

    // Username dell'utente attualmente loggato
    private static String currentLoggedUsername;

    // Parametri multicast letti da properties
    private static String multicastAddress;
    private static int multicastPort;


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
            multicastAddress = parseRequiredStringProperty(config, "multicast.address");
            multicastPort = parseRequiredIntProperty(config, "multicast.port");
            udpNotificationPort = parseRequiredIntProperty(config, "UDP.notification.port");

        } catch (IllegalArgumentException e) {
            System.err.println("[Client] Errore nel parsing dei parametri: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            // Inizializza thread pool per le operazioni
            pool = Executors.newCachedThreadPool();

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
                        handleUpdateCredentials(parts);
                        break;

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

                    case "getpricehistory":
                        handleGetPriceHistory(parts);
                        break;

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
     * Stampa la lista completa dei comandi
     */
    private static void printHelp() {
        System.out.println("\n=== COMANDI DISPONIBILI ===");
        System.out.println("help                                               - Mostra questo messaggio");
        System.out.println("register <username> <password>                     - Registra nuovo utente");
        System.out.println("login <username> <password>                        - Effettua login");
        System.out.println("logout                                             - Disconnette dal server");
        System.out.println("updateCredentials <username> <old_pwd> <new_pwd>   - Aggiorna password");
        System.out.println("insertLimitOrder <bid/ask> <size> <price>          - Inserisce ordine limite");
        System.out.println("insertMarketOrder <bid/ask> <size>                 - Inserisce ordine a mercato");
        System.out.println("insertStopOrder <bid/ask> <size> <stopPrice>       - Inserisce stop order");
        System.out.println("cancelOrder <orderId>                              - Cancella ordine");
        System.out.println("getPriceHistory <MMYYYY>                           - Ottieni storico prezzi mensile");
        System.out.println("registerPriceAlert <soglia>                        - Registra notifica soglia prezzo");
        System.out.println("esci                                               - Termina il client");
        System.out.println();
    }

    /**
     * Gestisce la registrazione di un nuovo utente via RMI
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

            System.out.println("[Client] Tentativo di login come: " + username);

            // Crea connessione TCP con il server
            tcpSocket = new Socket(serverHost, tcpPort);
            tcpSocket.setSoTimeout(socketTimeout);

            // Inizializza stream per comunicazione JSON
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);

            System.out.println("[Client] Connessione TCP stabilita con " + serverHost + ":" + tcpPort);

            // ===== AVVIO LISTENER UDP PRIMA DEL LOGIN =====
            // Il listener UDP deve essere avviato PRIMA di inviare il login al server
            // perché il server ha bisogno di sapere la porta UDP effettiva
            int actualUdpPort = startUDPListenerBeforeLogin(username);

            if (actualUdpPort == -1) {
                System.err.println("[Client] Impossibile avviare listener UDP, login annullato");
                closeConnection();
                return;
            }

            // Crea richiesta JSON di login con porta UDP effettiva
            JsonObject loginRequest = new JsonObject();
            loginRequest.addProperty("operation", "login");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("password", password);
            values.addProperty("udpPort", actualUdpPort); // Porta UDP effettiva assegnata

            loginRequest.add("values", values);

            // Invia richiesta al server
            String requestJson = loginRequest.toString();
            out.println(requestJson);

            System.out.println("[Client] Richiesta login inviata (porta UDP: " + actualUdpPort + ")");

            // Legge risposta dal server
            String responseLine = in.readLine();
            if (responseLine == null) {
                System.err.println("[Client] Server ha chiuso la connessione");
                stopUDPListener();
                closeConnection();
                return;
            }

            // Parsing risposta JSON
            JsonObject response = JsonParser.parseString(responseLine).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.has("errorMessage") ?
                    response.get("errorMessage").getAsString() : "";

            boolean loginSuccessful = false;

            // Interpreta codice risposta
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] === Login effettuato con successo ===");
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

            // Se login fallito, chiudi connessione e ferma listener UDP
            if (!loginSuccessful) {
                stopUDPListener();
                closeConnection();
                return;
            }

            // Se login successful, avvia listener multicast
            startMulticastListener();

            // Il listener UDP è già avviato, deve solo iniziare a processare i messaggi
            startUDPListenerThread();

        } catch (Exception e) {
            System.err.println("[Client] Errore durante login: " + e.getMessage());
            e.printStackTrace();

            // In caso di errore, ferma tutto
            stopUDPListener();
            closeConnection();
        }
    }

    /**
     * Avvia il listener UDP PRIMA del login per ottenere la porta effettiva
     * @param username username dell'utente
     * @return porta UDP effettiva, o -1 in caso di errore
     */
    private static int startUDPListenerBeforeLogin(String username) {
        try {
            // Crea listener UDP con porta da properties (può essere 0 per porta automatica)
            udpListener = new UDPNotificationListener(udpNotificationPort, username);

            // Avvia il socket UDP (questo assegna la porta)
            udpListener.start();

            // Ottiene la porta effettiva assegnata dal sistema
            int actualPort = udpListener.getActualPort();

            System.out.println("[Client] Listener UDP creato sulla porta: " + actualPort);

            return actualPort;

        } catch (Exception e) {
            System.err.println("[Client] Errore creazione listener UDP: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Avvia il thread del listener UDP (dopo login successful)
     */
    private static void startUDPListenerThread() {
        if (udpListener != null && currentLoggedUsername != null) {
            udpListenerThread = new Thread(udpListener, "UDP-Notification-Listener-" + currentLoggedUsername);
            udpListenerThread.setDaemon(true);
            udpListenerThread.start();

            System.out.println("[Client] Thread listener UDP avviato");
        }
    }

    /**
     * Ferma il listener UDP
     */
    private static void stopUDPListener() {
        if (udpListener != null) {
            udpListener.stop();

            if (udpListenerThread != null) {
                udpListenerThread.interrupt();
            }

            udpListener = null;
            udpListenerThread = null;

            System.out.println("[Client] Listener UDP fermato");
        }
    }

    /**
     * Chiude la connessione TCP
     */
    private static void closeConnection() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (tcpSocket != null) tcpSocket.close();

            tcpSocket = null;
            in = null;
            out = null;
        } catch (IOException e) {
            System.err.println("[Client] Errore chiusura connessione: " + e.getMessage());
        }
    }

    /**
     * Gestisce il logout dell'utente
     */
    private static void handleLogout() {
        try {
            // Verifica se c'è una connessione attiva
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Nessun utente loggato");
                return;
            }

            System.out.println("[Client] Invio richiesta di logout...");

            // Crea richiesta JSON di logout
            JsonObject logoutRequest = new JsonObject();
            logoutRequest.addProperty("operation", "logout");
            logoutRequest.add("values", new JsonObject());

            // Invia richiesta
            String requestJson = logoutRequest.toString();
            out.println(requestJson);

            // Legge risposta
            String responseLine = in.readLine();
            if (responseLine == null) {
                System.err.println("[Client] Server ha chiuso la connessione");
                cleanupAfterLogout();
                return;
            }

            // Parsing risposta JSON
            JsonObject response = JsonParser.parseString(responseLine).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();
            String errorMessage = response.has("errorMessage") ?
                    response.get("errorMessage").getAsString() : "";

            // Interpreta codice risposta
            switch (responseCode) {
                case 100:
                    System.out.println("[Client] Logout effettuato con successo");
                    break;
                case 101:
                    System.out.println("[Client] Errore durante logout: " + errorMessage);
                    break;
                default:
                    System.out.println("[Client] Risposta logout sconosciuta (codice " + responseCode + ")");
                    break;
            }

            // Pulizia risorse client-side
            cleanupAfterLogout();

        } catch (Exception e) {
            System.err.println("[Client] Errore durante logout: " + e.getMessage());
            cleanupAfterLogout();
        }
    }

    /**
     * Pulisce tutte le risorse dopo il logout
     */
    private static void cleanupAfterLogout() {
        // Ferma listener multicast
        stopMulticastListener();

        // Ferma listener UDP
        stopUDPListener();

        // Chiudi connessione TCP
        closeConnection();

        // Resetta username
        currentLoggedUsername = null;

        System.out.println("[Client] Risorse rilasciate, pronto per nuovo login");
    }

    /**
     * Gestisce comando updateCredentials dell'utente
     */
    private static void handleUpdateCredentials(String[] parts) {
        try {
            // Validazione parametri
            if (parts.length != 4) {
                System.out.println("[Client] Uso: updateCredentials <username> <old_password> <new_password>");
                return;
            }

            String username = parts[1];
            String oldPassword = parts[2];
            String newPassword = parts[3];

            if (username.trim().isEmpty() || oldPassword.trim().isEmpty() || newPassword.trim().isEmpty()) {
                System.out.println("[Client] Tutti i parametri sono obbligatori e non possono essere vuoti");
                return;
            }

            // Controllo connessione TCP
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Errore: non connesso al server. Effettuare login prima.");
                return;
            }

            // Costruzione richiesta JSON secondo ALLEGATO 1
            JsonObject request = new JsonObject();
            request.addProperty("operation", "updateCredentials");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("old_password", oldPassword);
            values.addProperty("new_password", newPassword);
            request.add("values", values);

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            // Gestione codici risposta
            if (response.has("response")) {
                int responseCode = response.get("response").getAsInt();
                String errorMsg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "";

                switch (responseCode) {
                    case 100:
                        System.out.println("[Client] Password aggiornata con successo!");
                        break;
                    case 101:
                        System.out.println("[Client] Errore: nuova password non valida - " + errorMsg);
                        break;
                    case 102:
                        System.out.println("[Client] Errore: username/password errati o utente inesistente - " + errorMsg);
                        break;
                    case 103:
                        System.out.println("[Client] Errore: nuova password uguale alla precedente - " + errorMsg);
                        break;
                    case 104:
                        System.out.println("[Client] Errore: utente attualmente loggato - " + errorMsg);
                        break;
                    case 105:
                        System.out.println("[Client] Errore interno del server - " + errorMsg);
                        break;
                    default:
                        System.out.println("[Client] Errore sconosciuto (codice " + responseCode + ") - " + errorMsg);
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante updateCredentials: " + e.getMessage());
        }
    }

    /**
     * Gestisce comando insertLimitOrder dell'utente
     */
    private static void handleInsertLimitOrder(String[] parts) {
        try {
            // Validazione parametri
            if (parts.length != 4) {
                System.out.println("[Client] Uso: insertLimitOrder <type> <size> <price>");
                System.out.println("[Client] type: bid (acquisto) o ask (vendita)");
                System.out.println("[Client] size: quantità in millesimi di BTC (1000 = 1 BTC)");
                System.out.println("[Client] price: prezzo in millesimi di USD (58000000 = 58.000 USD)");
                return;
            }

            String type = parts[1].toLowerCase();
            int size, price;

            try {
                size = Integer.parseInt(parts[2]);
                price = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                System.out.println("[Client] Errore: size e price devono essere numeri interi");
                return;
            }

            if (!type.equals("bid") && !type.equals("ask")) {
                System.out.println("[Client] Errore: type deve essere 'bid' o 'ask'");
                return;
            }

            if (size <= 0 || price <= 0) {
                System.out.println("[Client] Errore: size e price devono essere positivi");
                return;
            }

            // Controllo connessione TCP
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Errore: non connesso al server. Effettuare login prima.");
                return;
            }

            // Costruzione richiesta JSON secondo ALLEGATO 1
            JsonObject request = new JsonObject();
            request.addProperty("operation", "insertLimitOrder");

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            values.addProperty("price", price);
            request.add("values", values);

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            // Gestione risposta
            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("[Client] Errore inserimento Limit Order");
                } else {
                    System.out.println("[Client] Limit Order creato con successo!");
                    System.out.println("[Client] Order ID: " + orderId);
                    System.out.println("[Client] Tipo: " + type + ", Size: " + formatSize(size) + " BTC, Prezzo: " + formatPrice(price) + " USD");
                }
            } else {
                System.out.println("[Client] Risposta server non valida");
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante insertLimitOrder: " + e.getMessage());
        }
    }

    /**
     * Gestisce comando insertMarketOrder dell'utente
     */
    private static void handleInsertMarketOrder(String[] parts) {
        try {
            // Validazione parametri
            if (parts.length != 3) {
                System.out.println("[Client] Uso: insertMarketOrder <type> <size>");
                System.out.println("[Client] type: bid (acquisto) o ask (vendita)");
                System.out.println("[Client] size: quantità in millesimi di BTC (1000 = 1 BTC)");
                return;
            }

            String type = parts[1].toLowerCase();
            int size;

            try {
                size = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                System.out.println("[Client] Errore: size deve essere un numero intero");
                return;
            }

            if (!type.equals("bid") && !type.equals("ask")) {
                System.out.println("[Client] Errore: type deve essere 'bid' o 'ask'");
                return;
            }

            if (size <= 0) {
                System.out.println("[Client] Errore: size deve essere positivo");
                return;
            }

            // Controllo connessione TCP
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Errore: non connesso al server. Effettuare login prima.");
                return;
            }

            // Costruzione richiesta JSON
            JsonObject request = new JsonObject();
            request.addProperty("operation", "insertMarketOrder");

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            request.add("values", values);

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            // Gestione risposta
            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("[Client] Market Order rifiutato");
                } else {
                    System.out.println("[Client] Market Order eseguito!");
                    System.out.println("[Client] Order ID: " + orderId);
                    System.out.println("[Client] Tipo: " + type + ", Size: " + formatSize(size) + " BTC");
                    System.out.println("[Client] Eseguito al prezzo di mercato corrente");
                }
            } else {
                System.out.println("[Client] Risposta server non valida");
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante insertMarketOrder: " + e.getMessage());
        }
    }

    /**
     * Gestisce comando insertStopOrder dell'utente
     */
    private static void handleInsertStopOrder(String[] parts) {
        try {
            // Validazione parametri
            if (parts.length != 4) {
                System.out.println("[Client] Uso: insertStopOrder <type> <size> <stopPrice>");
                return;
            }

            String type = parts[1].toLowerCase();
            int size, stopPrice;

            try {
                size = Integer.parseInt(parts[2]);
                stopPrice = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                System.out.println("[Client] Errore: size e stopPrice devono essere numeri interi");
                return;
            }

            if (!type.equals("bid") && !type.equals("ask")) {
                System.out.println("[Client] Errore: type deve essere 'bid' o 'ask'");
                return;
            }

            if (size <= 0 || stopPrice <= 0) {
                System.out.println("[Client] Errore: size e stopPrice devono essere positivi");
                return;
            }

            // Controllo connessione TCP
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Errore: non connesso al server. Effettuare login prima.");
                return;
            }

            // Costruzione richiesta JSON
            JsonObject request = new JsonObject();
            request.addProperty("operation", "insertStopOrder");

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            values.addProperty("price", stopPrice);
            request.add("values", values);

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            // Gestione risposta
            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("[Client] Errore inserimento Stop Order (verifica che stopPrice sia corretto)");
                } else {
                    System.out.println("[Client] Stop Order creato con successo!");
                    System.out.println("[Client] Order ID: " + orderId);
                    System.out.println("[Client] Tipo: " + type + ", Size: " + formatSize(size) + " BTC");
                    System.out.println("[Client] Stop Price: " + formatPrice(stopPrice) + " USD");
                }
            } else {
                System.out.println("[Client] Risposta server non valida");
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante insertStopOrder: " + e.getMessage());
        }
    }

    /**
     * Gestisce comando cancelOrder dell'utente
     */
    private static void handleCancelOrder(String[] parts) {
        try {
            // Validazione parametri
            if (parts.length != 2) {
                System.out.println("[Client] Uso: cancelOrder <orderId>");
                return;
            }

            int orderId;
            try {
                orderId = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                System.out.println("[Client] Errore: orderId deve essere un numero intero");
                return;
            }

            if (orderId <= 0) {
                System.out.println("[Client] Errore: orderId deve essere positivo");
                return;
            }

            // Controllo connessione TCP
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("[Client] Errore: non connesso al server. Effettuare login prima.");
                return;
            }

            // Costruzione richiesta JSON
            JsonObject request = new JsonObject();
            request.addProperty("operation", "cancelOrder");

            JsonObject values = new JsonObject();
            values.addProperty("orderId", orderId);
            request.add("values", values);

            // Invio richiesta
            out.println(request.toString());

            // Lettura risposta
            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("[Client] Errore: connessione al server persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            // Gestione codici risposta
            if (response.has("response")) {
                int responseCode = response.get("response").getAsInt();
                String errorMsg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "";

                switch (responseCode) {
                    case 100:
                        System.out.println("[Client] Ordine " + orderId + " cancellato con successo!");
                        break;
                    case 101:
                        System.out.println("[Client] Errore: ordine non esistente, già eseguito, o non di tua proprietà - " + errorMsg);
                        break;
                    default:
                        System.out.println("[Client] Errore cancellazione ordine (codice " + responseCode + ") - " + errorMsg);
                }
            } else {
                System.out.println("[Client] Risposta server non valida");
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore durante cancelOrder: " + e.getMessage());
        }
    }

    /**
     * Gestisce comando getPriceHistory
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

            // Costruzione richiesta JSON
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
     * Visualizza i dati storici dei prezzi
     */
    private static void displayPriceHistory(String month, int totalDays, JsonArray historyData) {
        System.out.println("\n================================================================================");
        System.out.println("STORICO PREZZI BTC - " + formatMonthYear(month));
        System.out.println("================================================================================");

        if (totalDays == 0) {
            System.out.println("Nessun dato disponibile per il mese richiesto.");
            System.out.println("Potrebbe essere necessario eseguire alcune operazioni di trading prima.");
            System.out.println("================================================================================");
            return;
        }

        System.out.println("Giorni con dati: " + totalDays);
        System.out.println();

        // Header tabella
        System.out.printf("%-12s %-10s %-10s %-10s %-10s %-12s %-8s%n",
                "Data", "Apertura", "Massimo", "Minimo", "Chiusura", "Volume", "Trade");
        System.out.println("--------------------------------------------------------------------------------");

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

        System.out.println("================================================================================");
        System.out.println("Prezzi in USD, Volume in BTC");
    }

    /**
     * Gestisce comando per registrare notifiche soglia prezzo
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
                    System.out.println("[Client] Notifiche prezzo registrate con successo!");
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

    /**
     * Formatta mese da MMYYYY
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
            return monthYear;
        }
    }

    /**
     * Formatta data da YYYY-MM-DD a formato leggibile
     */
    private static String formatDate(String dateStr) {
        try {
            String[] parts = dateStr.split("-");
            return parts[2] + "/" + parts[1] + "/" + parts[0];
        } catch (Exception e) {
            return dateStr;
        }
    }

    /**
     * Formatta volume in formato leggibile
     */
    private static String formatVolume(int volumeInMillis) {
        return String.format("%.3f BTC", volumeInMillis / 1000.0);
    }

    /**
     * Formatta prezzo in millesimi
     */
    private static String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Formatta size in millesimi
     */
    private static String formatSize(int sizeInMillis) {
        return String.format("%.3f", sizeInMillis / 1000.0);
    }

    /**
     * Esegue lo shutdown ordinato del client
     */
    private static void shutdownClient() {
        System.out.println("[Client] Iniziando shutdown del client...");

        running = false;

        // Ferma listener multicast
        stopMulticastListener();

        // Ferma listener UDP
        stopUDPListener();

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
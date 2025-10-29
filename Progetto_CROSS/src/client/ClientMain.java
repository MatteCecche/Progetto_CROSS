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

/*
 * Client principale del sistema CROSS
 */
public class ClientMain {

    // Controllo stato client
    private static volatile boolean running = true;

    // Socket TCP per la connessione
    private static Socket tcpSocket;

    // Stream di input per leggere le risposte dal server
    private static BufferedReader in;

    // Stream di output per inviare richieste al server
    private static PrintWriter out;

    // Thread pool per gestione client
    private static ExecutorService pool;

    //Scanner per lettura input dal terminale
    private static Scanner userScanner;

    // Listener per messaggi multicast relativi alle notifiche di soglia prezzo
    private static MulticastListener multicastListener;

    // Thread dedicato per esecuzione del listener multicast
    private static Thread multicastThread;

    // Listener UDP per notifiche asincrone di esecuzione ordini
    private static UDPNotificationListener udpListener;

    // Thread dedicato per esecuzione del listener UDP
    private static Thread udpListenerThread;

    // Porta UDP locale su cui il client riceve notifiche di trade dal server
    private static int udpNotificationPort;

    // Username dell'utente attualmente autenticato nel sistema
    private static String currentLoggedUsername;

    // Indirizzo IP del gruppo multicast per notifiche soglia prezzo
    private static String multicastAddress;

    // Porta del gruppo multicast per notifiche soglia prezzo
    private static int multicastPort;

    // Lock per scrittura sincronizzata nel terminale
    private static final Object consoleLock = new Object();

    // Colori per risposte
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_CYAN = "\u001B[36m";
    public static final String ANSI_BOLD = "\u001B[1m";

    public static void main(String[] args) {

        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println(ANSI_RED + "Errore caricamento config: " + e.getMessage() + ANSI_RESET);
            System.exit(1);
            return;
        }

        String serverHost;
        int tcpPort, rmiPort, socketTimeout;

        try {
            serverHost = parseString(config, "server.host");
            tcpPort = parseInt(config, "TCP.port");
            rmiPort = parseInt(config, "RMI.port");
            socketTimeout = parseInt(config, "socket.timeout");
            multicastAddress = parseString(config, "multicast.address");
            multicastPort = parseInt(config, "multicast.port");
            udpNotificationPort = parseInt(config, "UDP.notification.port");

        } catch (IllegalArgumentException e) {
            System.err.println(ANSI_RED + "Errore parsing config: " + e.getMessage() + ANSI_RESET);
            System.exit(1);
            return;
        }

        try {
            pool = Executors.newCachedThreadPool();
            userScanner = new Scanner(System.in);
            startUserInterface(serverHost, tcpPort, rmiPort, socketTimeout);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        } finally {
            shutdown();
        }
    }

    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();
        File configFile = new File("src/client/client.properties");

        if (!configFile.exists()) {
            throw new IOException(ANSI_RED + "Errore: File config non trovato: " + configFile.getPath() + ANSI_RESET);
        }

        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
        }

        return prop;
    }

    private static int parseInt(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(ANSI_RED + "Errore: Parametro mancante: " + key + ANSI_RESET);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ANSI_RED + "Errore: Valore non valido per " + key + ANSI_RESET);
        }
    }

    private static String parseString(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(ANSI_RED + "Errore: Parametro mancante: " + key + ANSI_RESET);
        }
        return value.trim();
    }

    private static void startUserInterface(String serverHost, int tcpPort, int rmiPort, int socketTimeout) {
        System.out.println();
        System.out.println(ANSI_CYAN + "==================== CROSS: an exChange oRder bOokS Service ====================" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "====================== Digitare 'help' per lista comandi  ======================" + ANSI_RESET);

        while (running) {
            synchronized (consoleLock) {
                System.out.print(ANSI_BOLD + "\r>> " + ANSI_RESET);
            }

            String inputLine = userScanner.nextLine().trim();

            if (inputLine.isEmpty()) {
                continue;
            }

            String[] parts = inputLine.split("\\s+");
            String command = parts[0].toLowerCase();

            try {
                synchronized (consoleLock) {
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
                            handleUpdateCredentials(parts, serverHost, tcpPort);
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
                            running = false;
                            break;
                        default:
                            System.out.println("Comando non riconosciuto. Digitare 'help'");
                            break;
                    }
                }
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
            }
        }
    }

    private static void printHelp() {
        System.out.println(ANSI_YELLOW + repeat("=", 80) + ANSI_RESET);
        System.out.println(ANSI_RED + "=                             COMANDI DISPONIBILI                              =" + ANSI_RESET);
        System.out.println(ANSI_YELLOW  + repeat("=", 80) + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  help                                                                        =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  register <username> <password>                                              =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  login <username> <password>                                                 =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  logout                                                                      =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  updateCredentials <username> <old_pwd> <new_pwd>                            =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  insertLimitOrder <bid/ask> <size> <price>                                   =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  insertMarketOrder <bid/ask> <size>                                          =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  insertStopOrder <bid/ask> <size> <stopPrice>                                =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  cancelOrder <orderId>                                                       =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  getPriceHistory <MMYYYY>                                                    =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  registerPriceAlert <soglia>                                                 =" + ANSI_RESET);
        System.out.println(ANSI_GREEN + "=  esci                                                                        =" + ANSI_RESET);
        System.out.println(ANSI_YELLOW + repeat("=", 80) + ANSI_RESET);
    }

    private static void handleRegistration(String[] parts, String serverHost, int rmiPort) {
        try {
            if (parts.length != 3) {
                System.out.println(ANSI_RED + "Errore: register <username> <password>" + ANSI_RESET);
                return;
            }

            String username = parts[1];
            String password = parts[2];

            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                System.out.println(ANSI_RED + "Errore: Username e password non possono essere vuoti" + ANSI_RESET);
                return;
            }

            Registry registry = LocateRegistry.getRegistry(serverHost, rmiPort);
            RegistrazioneRMI stub = (RegistrazioneRMI) registry.lookup("server-rmi");
            int responseCode = stub.register(username, password);

            switch (responseCode) {
                case 100:
                    System.out.println(ANSI_GREEN + "Registrazione completata!" + ANSI_RESET);
                    break;
                case 101:
                    System.out.println(ANSI_RED + "Errore: Password non valida" + ANSI_RESET);
                    break;
                case 102:
                    System.out.println(ANSI_RED + "Errore: Username già esistente" + ANSI_RESET);
                    break;
                default:
                    System.out.println(ANSI_RED + "Errore registrazione" + ANSI_RESET);
                    break;
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore RMI: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void handleLogin(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        try {
            if (parts.length != 3) {
                System.out.println(ANSI_RED + "Errore: login <username> <password>" + ANSI_RESET);
                return;
            }

            if (tcpSocket != null && !tcpSocket.isClosed()) {
                System.out.println(ANSI_RED + "Errore: Già connesso, eseguire logout prima" + ANSI_RESET);
                return;
            }

            String username = parts[1];
            String password = parts[2];

            tcpSocket = new Socket(serverHost, tcpPort);
            tcpSocket.setSoTimeout(socketTimeout);
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);

            startUDPListener(username);

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("password", password);
            values.addProperty("udpPort", udpListener.getActualPort());

            JsonObject request = createRequest("login", values);
            JsonObject response = sendRequestAndGetResponse(request);

            if (response == null) {
                return;
            }

            int responseCode = response.get("response").getAsInt();

            if (responseCode == 100) {
                currentLoggedUsername = username;
                System.out.println(ANSI_GREEN + "Login effettuato: " + username + ANSI_RESET);
                startMulticastListener();
            } else {
                String errorMsg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "Errore login";
                System.out.println(ANSI_RED + errorMsg + ANSI_RESET);
                closeConnection();
            }

        } catch (IOException e) {
            System.err.println(ANSI_RED + "Errore connessione: " + e.getMessage() + ANSI_RESET);
            closeConnection();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
            closeConnection();
        }
    }

    private static void handleLogout() {
        try {
            if (isNotLoggedIn()) {
                return;
            }

            JsonObject logoutRequest = createRequest("logout", new JsonObject());
            JsonObject response = sendRequestAndGetResponse(logoutRequest);

            if (response != null) {
                System.out.println(ANSI_GREEN + "Logout effettuato" + ANSI_RESET);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore logout: " + e.getMessage() + ANSI_RESET);
        } finally {
            closeConnection();
            stopMulticastListener();
            stopUDPListener();
        }
    }

    private static void handleUpdateCredentials(String[] parts, String serverHost, int tcpPort) {
        try {
            if (parts.length != 4) {
                System.out.println(ANSI_RED + "Errore: updateCredentials <username> <old_password> <new_password>" + ANSI_RESET);
                return;
            }

            String username = parts[1];
            String oldPassword = parts[2];
            String newPassword = parts[3];

            if (username.trim().isEmpty() || oldPassword.trim().isEmpty() || newPassword.trim().isEmpty()) {
                System.out.println(ANSI_RED + "Errore: Tutti i campi sono obbligatori" + ANSI_RESET);
                return;
            }

            Socket tempSocket = null;
            BufferedReader tempIn = null;
            PrintWriter tempOut = null;

            try {
                tempSocket = new Socket(serverHost, tcpPort);
                tempIn = new BufferedReader(new InputStreamReader(tempSocket.getInputStream()));
                tempOut = new PrintWriter(tempSocket.getOutputStream(), true);

                JsonObject values = new JsonObject();
                values.addProperty("username", username);
                values.addProperty("old_password", oldPassword);
                values.addProperty("new_password", newPassword);

                JsonObject request = createRequest("updateCredentials", values);
                tempOut.println(request);

                String responseStr = tempIn.readLine();
                if (responseStr == null) {
                    System.out.println(ANSI_RED + "Errore: nessuna risposta dal server" + ANSI_RESET);
                    return;
                }

                JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
                int responseCode = response.get("response").getAsInt();

                switch (responseCode) {
                    case 100:
                        System.out.println(ANSI_GREEN + "Password aggiornata con successo" + ANSI_RESET);
                        break;
                    case 101:
                        System.out.println(ANSI_RED + "Errore: Password non valida" + ANSI_RESET);
                        break;
                    case 102:
                        System.out.println(ANSI_RED + "Errore: Username non trovato o password errata" + ANSI_RESET);
                        break;
                    case 103:
                        System.out.println(ANSI_RED + "Errore: La nuova password deve essere diversa dalla vecchia" + ANSI_RESET);
                        break;
                    case 104:
                        System.out.println(ANSI_RED + "Errore: Impossibile aggiornare: utente loggato" + ANSI_RESET);
                        break;
                    default:
                        System.out.println(ANSI_RED + "Errore aggiornamento password" + ANSI_RESET);
                        break;
                }

            } finally {
                if (tempIn != null) tempIn.close();
                if (tempOut != null) tempOut.close();
                if (tempSocket != null && !tempSocket.isClosed()) tempSocket.close();
            }

        } catch (IOException e) {
            System.err.println(ANSI_RED + "Errore I/O: " + e.getMessage() + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void handleInsertLimitOrder(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println(ANSI_RED + "Errore: insertLimitOrder <bid/ask> <size> <price>" + ANSI_RESET);
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            String type = parts[1];
            int size = Integer.parseInt(parts[2]);
            int price = Integer.parseInt(parts[3]);

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            values.addProperty("price", price);

            JsonObject request = createRequest("insertLimitOrder", values);
            JsonObject response = sendRequestAndGetResponse(request);

            handleOrderResponse(response, "Limit Order creato", "Errore inserimento ordine");

        } catch (NumberFormatException e) {
            System.out.println(ANSI_RED + "Errore: size e price devono essere numeri" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void handleInsertMarketOrder(String[] parts) {
        try {
            if (parts.length != 3) {
                System.out.println(ANSI_RED + "Errore: insertMarketOrder <bid/ask> <size>" + ANSI_RESET);
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            String type = parts[1];
            int size = Integer.parseInt(parts[2]);

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);

            JsonObject request = createRequest("insertMarketOrder", values);
            JsonObject response = sendRequestAndGetResponse(request);

            handleOrderResponse(response, "Market Order eseguito", "Market Order rifiutato");

        } catch (NumberFormatException e) {
            System.out.println(ANSI_RED + "Errore: size deve essere un numero" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void handleInsertStopOrder(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println(ANSI_RED + "Errore: insertStopOrder <bid/ask> <size> <stopPrice>" + ANSI_RESET);
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            String type = parts[1];
            int size = Integer.parseInt(parts[2]);
            int stopPrice = Integer.parseInt(parts[3]);

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            values.addProperty("price", stopPrice);

            JsonObject request = createRequest("insertStopOrder", values);
            JsonObject response = sendRequestAndGetResponse(request);

            handleOrderResponse(response, "Stop Order creato", "Stop Order rifiutato");

        } catch (NumberFormatException e) {
            System.out.println(ANSI_RED + "Errore: size e stopPrice devono essere numeri" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void handleCancelOrder(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println(ANSI_RED + "Errore: cancelOrder <orderId>" + ANSI_RESET);
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            int orderId = Integer.parseInt(parts[1]);

            JsonObject values = new JsonObject();
            values.addProperty("orderId", orderId);

            JsonObject request = createRequest("cancelOrder", values);
            JsonObject response = sendRequestAndGetResponse(request);

            if (response == null) {
                return;
            }

            int responseCode = response.get("response").getAsInt();
            if (responseCode == 100) {
                System.out.println(ANSI_GREEN + "Ordine cancellato con successo" + ANSI_RESET);
            } else {
                String errorMsg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "Impossibile cancellare ordine";
                System.out.println(errorMsg);
            }

        } catch (NumberFormatException e) {
            System.out.println(ANSI_RED + "Errore: orderId deve essere un numero" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void handleGetPriceHistory(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println(ANSI_RED + "Errore: getPriceHistory <MMYYYY>" + ANSI_RESET);
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            String month = parts[1];

            if (month.length() != 6) {
                System.out.println(ANSI_RED + "Errore: Formato mese non valido. Usare MMYYYY" + ANSI_RESET);
                return;
            }

            JsonObject values = new JsonObject();
            values.addProperty("month", month);

            JsonObject request = createRequest("getPriceHistory", values);
            JsonObject response = sendRequestAndGetResponse(request);

            if (response == null) {
                return;
            }

            if (response.has("priceHistory")) {
                displayPriceHistory(response);
            } else if (response.has("errorMessage")) {
                System.out.println(ANSI_RED + "Errore: " + response.get("errorMessage").getAsString() + ANSI_RESET);
            } else {
                System.out.println(ANSI_RED + "Errore recupero storico" + ANSI_RESET);
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void displayPriceHistory(JsonObject response) {
        String month = response.get("month").getAsString();
        int totalDays = response.get("totalDays").getAsInt();
        JsonArray historyData = response.getAsJsonArray("priceHistory");

        String monthName = convertMonthToName(month);

        System.out.println(ANSI_YELLOW + "\n" + repeat("=", 80) + ANSI_RESET);
        String title = ANSI_RED +  "STORICO PREZZI - " + monthName + ANSI_RESET;
        int padding = (80 - title.length()) / 2;
        System.out.println(ANSI_YELLOW + repeat(" ", padding) + title + ANSI_RESET);
        System.out.println(ANSI_YELLOW + repeat("=", 80) + ANSI_RESET);

        if (totalDays == 0) {
            System.out.println(ANSI_GREEN + "Nessun dato disponibile" + ANSI_RESET);
            System.out.println(ANSI_YELLOW + repeat("=", 80) + ANSI_RESET);
            return;
        }

        System.out.printf(ANSI_RED + "%-12s %-10s %-10s %-10s %-10s%n", "Data", "Open", "High", "Low", "Close" + ANSI_RESET);
        System.out.println(ANSI_YELLOW + repeat("-", 80) + ANSI_RESET);

        for (int i = 0; i < historyData.size(); i++) {
            JsonObject day = historyData.get(i).getAsJsonObject();
            String date = day.get("date").getAsString();
            int open = day.get("openPrice").getAsInt();
            int high = day.get("highPrice").getAsInt();
            int low = day.get("lowPrice").getAsInt();
            int close = day.get("closePrice").getAsInt();

            System.out.printf(ANSI_GREEN + "%-12s %-10s %-10s %-10s %-10s%n", date, formatPrice(open), formatPrice(high), formatPrice(low), formatPrice(close) + ANSI_RESET);
        }

        System.out.println(ANSI_YELLOW + repeat("=", 80) + ANSI_RESET);
    }

    //Converte formato mese da MMYYYY a nome scritto
    private static String convertMonthToName(String monthCode) {
        if (monthCode == null || monthCode.length() != 6) {
            return monthCode;
        }

        String monthNum = monthCode.substring(0, 2);
        String year = monthCode.substring(2, 6);

        String[] monthNames = {
                "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
                "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
        };

        try {
            int month = Integer.parseInt(monthNum);
            if (month >= 1 && month <= 12) {
                return monthNames[month - 1] + " " + year;
            }
        } catch (NumberFormatException e) {
            // Non fa nulla
        }

        return monthCode;
    }

    private static void handleRegisterPriceAlert(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println(ANSI_RED + "Errore: registerPriceAlert <threshold>" + ANSI_RESET);
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            if (multicastListener == null) {
                System.out.println(ANSI_RED + "Errore: listener multicast non attivo" + ANSI_RESET);
                return;
            }

            int threshold = Integer.parseInt(parts[1]);

            if (threshold <= 0) {
                System.out.println(ANSI_RED + "Errore: la soglia deve essere un valore positivo" + ANSI_RESET);
                return;
            }

            JsonObject values = new JsonObject();
            values.addProperty("thresholdPrice", threshold);

            JsonObject request = createRequest("registerPriceAlert", values);
            JsonObject response = sendRequestAndGetResponse(request);

            if (response == null) {
                return;
            }

            int responseCode = response.get("response").getAsInt();

            if (responseCode == 100) {
                double thresholdUSD = threshold / 1000.0;
                System.out.println(ANSI_YELLOW + "\n" + repeat("=", 80));
                System.out.println(ANSI_RED + "           ALERT PREZZO REGISTRATO CON SUCCESSO");
                System.out.println(ANSI_YELLOW + repeat("=", 80));
                System.out.println();
                System.out.printf(ANSI_GREEN + "  %-20s : $%,.2f USD%n", "Soglia impostata", thresholdUSD);
                System.out.printf(ANSI_GREEN + "  %-20s : Multicast UDP%n", "Tipo notifica");
                System.out.printf(ANSI_GREEN + "  %-20s : %s%n", "Gruppo multicast", multicastAddress + ":" + multicastPort);
                System.out.println();
                System.out.println(ANSI_YELLOW + repeat("=", 80));
                System.out.println();
            } else {
                String errorMsg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "Errore registrazione alert";
                System.out.println(errorMsg);
            }

        } catch (NumberFormatException e) {
            System.out.println(ANSI_RED + "Errore: threshold deve essere un numero intero" + ANSI_RESET);
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore registerPriceAlert: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static boolean isNotLoggedIn() {
        if (tcpSocket == null || tcpSocket.isClosed() || currentLoggedUsername == null) {
            System.out.println(ANSI_RED + "Errore: effettuare login prima" + ANSI_RESET);
            return true;
        }
        return false;
    }

    private static JsonObject createRequest(String operation, JsonObject values) {
        JsonObject request = new JsonObject();
        request.addProperty("operation", operation);
        request.add("values", values);
        return request;
    }

    private static JsonObject sendRequestAndGetResponse(JsonObject request) {
        try {
            out.println(request);

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println(ANSI_RED + "Errore: Connessione persa" + ANSI_RESET);
                closeConnection();
                return null;
            }

            return JsonParser.parseString(responseStr).getAsJsonObject();

        } catch (IOException e) {
            System.err.println(ANSI_RED + "Errore comunicazione: " + e.getMessage() + ANSI_RESET);
            closeConnection();
            return null;
        }
    }

    private static void closeConnection() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
            }
            tcpSocket = null;
            currentLoggedUsername = null;
        } catch (IOException e) {
            // Ignora errori di chiusura
        }
    }

    private static void handleOrderResponse(JsonObject response, String successMessage, String errorMessage) {
        if (response == null) {
            return;
        }

        if (response.has("orderId")) {
            int orderId = response.get("orderId").getAsInt();
            if (orderId == -1) {
                System.out.println(errorMessage);
            } else {
                System.out.println(ANSI_GREEN + successMessage + ": ID=" + orderId + ANSI_RESET);
            }
        }
    }

    private static void startUDPListener(String username) {
        try {
            if (udpListener == null) {
                udpListener = new UDPNotificationListener(udpNotificationPort, username, consoleLock);
                udpListener.start();

                udpListenerThread = new Thread(udpListener, "UDP-Listener");
                udpListenerThread.setDaemon(true);
                udpListenerThread.start();
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore avvio UDP listener: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void stopUDPListener() {
        try {
            if (udpListener != null) {
                udpListener.stop();
                if (udpListenerThread != null && udpListenerThread.isAlive()) {
                    udpListenerThread.interrupt();
                    try {
                        udpListenerThread.join(2000);
                    } catch (InterruptedException e) {
                        System.err.println(ANSI_RED + "Errore: Interruzione durante stop UDP listener" + ANSI_RESET);
                    }
                }
                udpListener = null;
                udpListenerThread = null;
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore stop UDP listener: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void startMulticastListener() {
        try {
            if (multicastListener == null && currentLoggedUsername != null) {
                multicastListener = new MulticastListener(multicastAddress, multicastPort, currentLoggedUsername, consoleLock);
                multicastListener.start();

                multicastThread = new Thread(multicastListener, "Multicast-Listener");
                multicastThread.setDaemon(true);
                multicastThread.start();
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore multicast: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static void stopMulticastListener() {
        try {
            if (multicastListener != null) {
                multicastListener.stop();
                if (multicastThread != null && multicastThread.isAlive()) {
                    multicastThread.interrupt();
                    try {
                        multicastThread.join(2000);
                    } catch (InterruptedException e) {
                        System.err.println(ANSI_RED + "Errore: Interruzione durante stop multicast" + ANSI_RESET);
                    }
                }
                multicastListener = null;
                multicastThread = null;
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore stop multicast: " + e.getMessage() + ANSI_RESET);
        }
    }
    private static String formatPrice(int priceInMillis) {

        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    private static String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private static void shutdown() {
        running = false;
        stopMulticastListener();
        stopUDPListener();

        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (tcpSocket != null && !tcpSocket.isClosed()) {
                tcpSocket.close();
            }
        } catch (IOException e) {
            // Ignora errori di chiusura
        }

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

        if (userScanner != null) {
            userScanner.close();
        }
        System.out.println(ANSI_CYAN + "============================ Chiusura Client CROSS =============================" + ANSI_RESET);
        System.out.println();
    }
}
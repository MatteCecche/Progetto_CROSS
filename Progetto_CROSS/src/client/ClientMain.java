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

    public static void main(String[] args) {
        System.out.println("Avvio Client CROSS");

        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("Errore caricamento config: " + e.getMessage());
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
            System.err.println("Errore parsing config: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            pool = Executors.newCachedThreadPool();
            userScanner = new Scanner(System.in);
            startUserInterface(serverHost, tcpPort, rmiPort, socketTimeout);

        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();
        File configFile = new File("src/client/client.properties");

        if (!configFile.exists()) {
            throw new IOException("File config non trovato: " + configFile.getPath());
        }

        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
        }

        return prop;
    }

    private static int parseInt(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante: " + key);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valore non valido per " + key);
        }
    }

    private static String parseString(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante: " + key);
        }
        return value.trim();
    }

    private static void startUserInterface(String serverHost, int tcpPort, int rmiPort, int socketTimeout) {
        System.out.println("=== CROSS: an exChange oRder bOokS Service ===");
        System.out.println("Digitare 'help' per lista comandi");

        while (running) {
            System.out.print("\r>> ");
            String inputLine = userScanner.nextLine().trim();

            if (inputLine.isEmpty()) {
                continue;
            }

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
                        running = false;
                        break;
                    default:
                        System.out.println("Comando non riconosciuto. Digitare 'help'");
                        break;
                }
            } catch (Exception e) {
                System.err.println("[Client] Errore: " + e.getMessage());
            }
        }
    }

    private static void printHelp() {
        System.out.println("\n" + repeat("=", 65));
        System.out.println("=                      COMANDI DISPONIBILI                      =");
        System.out.println(repeat("=", 65));
        System.out.println("=  help                                                         =");
        System.out.println("=  register <username> <password>                               =");
        System.out.println("=  login <username> <password>                                  =");
        System.out.println("=  logout                                                       =");
        System.out.println("=  updateCredentials <username> <old_pwd> <new_pwd>             =");
        System.out.println("=  insertLimitOrder <bid/ask> <size> <price>                    =");
        System.out.println("=  insertMarketOrder <bid/ask> <size>                           =");
        System.out.println("=  insertStopOrder <bid/ask> <size> <stopPrice>                 =");
        System.out.println("=  cancelOrder <orderId>                                        =");
        System.out.println("=  getPriceHistory <MMYYYY>                                     =");
        System.out.println("=  registerPriceAlert <soglia>                                  =");
        System.out.println("=  esci                                                         =");
        System.out.println(repeat("=", 65));
    }

    private static void handleRegistration(String[] parts, String serverHost, int rmiPort) {
        try {
            if (parts.length != 3) {
                System.out.println("Errore: register <username> <password>");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                System.out.println("Username e password non possono essere vuoti");
                return;
            }

            Registry registry = LocateRegistry.getRegistry(serverHost, rmiPort);
            RegistrazioneRMI stub = (RegistrazioneRMI) registry.lookup("server-rmi");
            int responseCode = stub.register(username, password);

            switch (responseCode) {
                case 100:
                    System.out.println("Registrazione completata!");
                    break;
                case 101:
                    System.out.println("Password non valida");
                    break;
                case 102:
                    System.out.println("Username già esistente");
                    break;
                default:
                    System.out.println("Errore registrazione");
                    break;
            }

        } catch (Exception e) {
            System.err.println("Errore RMI: " + e.getMessage());
        }
    }

    private static void handleLogin(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        try {
            if (parts.length != 3) {
                System.out.println("Uso: login <username> <password>");
                return;
            }

            if (tcpSocket != null && !tcpSocket.isClosed()) {
                System.out.println("Già connesso. Eseguire logout prima");
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
                System.out.println("Login effettuato: " + username);
                startMulticastListener();
            } else {
                String errorMsg = response.has("errorMessage")
                        ? response.get("errorMessage").getAsString()
                        : "Errore login";
                System.out.println(errorMsg);
                closeConnection();
            }

        } catch (IOException e) {
            System.err.println("Errore connessione: " + e.getMessage());
            closeConnection();
        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
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
                System.out.println("Logout effettuato");
            }

        } catch (Exception e) {
            System.err.println("Errore logout: " + e.getMessage());
        } finally {
            closeConnection();
            stopMulticastListener();
            stopUDPListener();
        }
    }

    private static void handleUpdateCredentials(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println("Uso: updateCredentials <username> <old_password> <new_password>");
                return;
            }

            String username = parts[1];
            String oldPassword = parts[2];
            String newPassword = parts[3];

            if (username.trim().isEmpty() || oldPassword.trim().isEmpty() || newPassword.trim().isEmpty()) {
                System.out.println("Tutti i campi sono obbligatori");
                return;
            }

            Socket tempSocket = null;
            BufferedReader tempIn = null;
            PrintWriter tempOut = null;

            try {
                tempSocket = new Socket(tcpSocket.getInetAddress(), tcpSocket.getPort());
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
                    System.out.println("Errore: nessuna risposta dal server");
                    return;
                }

                JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
                int responseCode = response.get("response").getAsInt();

                switch (responseCode) {
                    case 100:
                        System.out.println("Password aggiornata con successo");
                        break;
                    case 101:
                        System.out.println("Password non valida");
                        break;
                    case 102:
                        System.out.println("Username non trovato o password errata");
                        break;
                    case 103:
                        System.out.println("La nuova password deve essere diversa dalla vecchia");
                        break;
                    case 104:
                        System.out.println("Impossibile aggiornare: utente loggato");
                        break;
                    default:
                        System.out.println("Errore aggiornamento password");
                        break;
                }

            } finally {
                if (tempIn != null) tempIn.close();
                if (tempOut != null) tempOut.close();
                if (tempSocket != null && !tempSocket.isClosed()) tempSocket.close();
            }

        } catch (IOException e) {
            System.err.println("Errore I/O: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        }
    }

    private static void handleInsertLimitOrder(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println("Uso: insertLimitOrder <bid/ask> <size> <price>");
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
            System.out.println("Errore: size e price devono essere numeri");
        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        }
    }

    private static void handleInsertMarketOrder(String[] parts) {
        try {
            if (parts.length != 3) {
                System.out.println("Uso: insertMarketOrder <bid/ask> <size>");
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
            System.out.println("Errore: size deve essere un numero");
        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        }
    }

    private static void handleInsertStopOrder(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println("Uso: insertStopOrder <bid/ask> <size> <stopPrice>");
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
            System.out.println("Errore: size e stopPrice devono essere numeri");
        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        }
    }

    private static void handleCancelOrder(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println("Uso: cancelOrder <orderId>");
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
                System.out.println("Ordine cancellato con successo");
            } else {
                String errorMsg = response.has("errorMessage")
                        ? response.get("errorMessage").getAsString()
                        : "Impossibile cancellare ordine";
                System.out.println(errorMsg);
            }

        } catch (NumberFormatException e) {
            System.out.println("Errore: orderId deve essere un numero");
        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        }
    }

    private static void handleGetPriceHistory(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println("Uso: getPriceHistory <MMYYYY>");
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            String month = parts[1];

            if (month.length() != 6) {
                System.out.println("Formato mese non valido. Usare MMYYYY (es: 012025)");
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
                System.out.println("Errore: " + response.get("errorMessage").getAsString());
            } else {
                System.out.println("Errore recupero storico");
            }

        } catch (Exception e) {
            System.err.println("Errore: " + e.getMessage());
        }
    }

    private static void displayPriceHistory(JsonObject response) {
        String month = response.get("month").getAsString();
        int totalDays = response.get("totalDays").getAsInt();
        JsonArray historyData = response.getAsJsonArray("priceHistory");

        String monthName = convertMonthToName(month);

        System.out.println("\n" + repeat("=", 80));

        String title = "STORICO PREZZI - " + monthName;
        int padding = (80 - title.length()) / 2;
        System.out.println(repeat(" ", padding) + title);
        System.out.println(repeat("=", 80));

        if (totalDays == 0) {
            System.out.println("Nessun dato disponibile");
            System.out.println(repeat("=", 80));
            return;
        }

        System.out.printf("%-12s %-10s %-10s %-10s %-10s%n", "Data", "Open", "High", "Low", "Close");
        System.out.println(repeat("-", 80));

        for (int i = 0; i < historyData.size(); i++) {
            JsonObject day = historyData.get(i).getAsJsonObject();
            String date = day.get("date").getAsString();
            int open = day.get("openPrice").getAsInt();
            int high = day.get("highPrice").getAsInt();
            int low = day.get("lowPrice").getAsInt();
            int close = day.get("closePrice").getAsInt();

            System.out.printf("%-12s %-10s %-10s %-10s %-10s%n",
                    date, formatPrice(open), formatPrice(high), formatPrice(low), formatPrice(close));
        }

        System.out.println(repeat("=", 80));
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
            // se parsing fallisce, ritorna il codice originale
        }

        return monthCode;
    }

    private static void handleRegisterPriceAlert(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println("Uso: registerPriceAlert <threshold>");
                return;
            }

            if (isNotLoggedIn()) {
                return;
            }

            if (multicastListener == null) {
                System.out.println("Errore: listener multicast non attivo");
                return;
            }

            int threshold = Integer.parseInt(parts[1]);

            if (threshold <= 0) {
                System.out.println("Errore: la soglia deve essere un valore positivo");
                return;
            }

            JsonObject values = new JsonObject();
            values.addProperty("thresholdPrice", threshold);  // ✅ CORRETTO: era "threshold"

            JsonObject request = createRequest("registerPriceAlert", values);
            JsonObject response = sendRequestAndGetResponse(request);

            if (response == null) {
                return;
            }

            int responseCode = response.get("response").getAsInt();

            if (responseCode == 100) {
                double thresholdUSD = threshold / 1000.0;
                System.out.println("\n" + repeat("=", 70));
                System.out.println("           ALERT PREZZO REGISTRATO CON SUCCESSO");
                System.out.println(repeat("=", 70));
                System.out.println();
                System.out.printf("  %-20s : $%,.2f USD%n", "Soglia impostata", thresholdUSD);
                System.out.printf("  %-20s : Multicast UDP%n", "Tipo notifica");
                System.out.printf("  %-20s : %s%n", "Gruppo multicast", multicastAddress + ":" + multicastPort);
                System.out.println();
                System.out.println(repeat("=", 70));
                System.out.println();
            } else {
                String errorMsg = response.has("errorMessage")
                        ? response.get("errorMessage").getAsString()
                        : "Errore registrazione alert";
                System.out.println(errorMsg);
            }

        } catch (NumberFormatException e) {
            System.out.println("Errore: threshold deve essere un numero intero");
        } catch (Exception e) {
            System.err.println("[Client] Errore registerPriceAlert: " + e.getMessage());
        }
    }

    private static boolean isNotLoggedIn() {
        if (tcpSocket == null || tcpSocket.isClosed() || currentLoggedUsername == null) {
            System.out.println("Errore: effettuare login prima");
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
                System.out.println("Connessione persa");
                closeConnection();
                return null;
            }

            return JsonParser.parseString(responseStr).getAsJsonObject();

        } catch (IOException e) {
            System.err.println("Errore comunicazione: " + e.getMessage());
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
                System.out.println(successMessage + ": ID=" + orderId);
            }
        }
    }

    private static void startUDPListener(String username) { // <-- ACCETTA USERNAME
        try {
            if (udpListener == null) {
                udpListener = new UDPNotificationListener(udpNotificationPort, username);
                udpListener.start();

                udpListenerThread = new Thread(udpListener, "UDP-Listener");
                udpListenerThread.setDaemon(true);
                udpListenerThread.start();
            }
        } catch (Exception e) {
            System.err.println("[Client] Errore avvio UDP listener: " + e.getMessage());
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
                        System.err.println("[Client] Interruzione durante stop UDP listener");
                    }
                }
                udpListener = null;
                udpListenerThread = null;
            }
        } catch (Exception e) {
            System.err.println("[Client] Errore stop UDP listener: " + e.getMessage());
        }
    }

    private static void startMulticastListener() {
        try {
            if (multicastListener == null && currentLoggedUsername != null) {
                multicastListener = new MulticastListener(multicastAddress, multicastPort, currentLoggedUsername);
                multicastListener.start();

                multicastThread = new Thread(multicastListener, "Multicast-Listener");
                multicastThread.setDaemon(true);
                multicastThread.start();
            }
        } catch (Exception e) {
            System.err.println("[Client] Errore multicast: " + e.getMessage());
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
                        System.err.println("[Client] Interruzione durante stop multicast");
                    }
                }
                multicastListener = null;
                multicastThread = null;
            }
        } catch (Exception e) {
            System.err.println("[Client] Errore stop multicast: " + e.getMessage());
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
        System.out.println("=========== Chiusura Client CROSS ============");
    }
}
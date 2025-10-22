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

    private static volatile boolean running = true;

    // Connessione TCP
    private static Socket tcpSocket;
    private static BufferedReader in;
    private static PrintWriter out;

    private static ExecutorService pool;
    private static Scanner userScanner;

    // Listener notifiche
    private static MulticastListener multicastListener;
    private static Thread multicastThread;
    private static UDPNotificationListener udpListener;
    private static Thread udpListenerThread;

    // Configurazione
    private static int udpNotificationPort;
    private static String currentLoggedUsername;
    private static String multicastAddress;
    private static int multicastPort;

    public static void main(String[] args) {
        System.out.println("[Client] Avvio Client CROSS");

        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("[Client] Errore caricamento config: " + e.getMessage());
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
            System.err.println("[Client] Errore parsing config: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            pool = Executors.newCachedThreadPool();
            userScanner = new Scanner(System.in);
            startUserInterface(serverHost, tcpPort, rmiPort, socketTimeout);

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
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
        System.out.println("\n=== CROSS: an exChange oRder bOokS Service ===");
        System.out.println("Digitare 'help' per lista comandi\n");

        while (running) {
            System.out.print(">> ");
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
        System.out.println("\n=== COMANDI ===");
        System.out.println("help");
        System.out.println("register <username> <password>");
        System.out.println("login <username> <password>");
        System.out.println("logout");
        System.out.println("updateCredentials <username> <old_pwd> <new_pwd>");
        System.out.println("insertLimitOrder <bid/ask> <size> <price>");
        System.out.println("insertMarketOrder <bid/ask> <size>");
        System.out.println("insertStopOrder <bid/ask> <size> <stopPrice>");
        System.out.println("cancelOrder <orderId>");
        System.out.println("getPriceHistory <MMYYYY>");
        System.out.println("registerPriceAlert <soglia>");
        System.out.println("esci\n");
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
                    System.out.println("Errore: password non valida");
                    break;
                case 102:
                    System.out.println("Errore: username già in uso");
                    break;
                case 103:
                    System.out.println("Errore interno del server");
                    break;
                default:
                    System.out.println("Errore registrazione (codice " + responseCode + ")");
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore registrazione: " + e.getMessage());
        }
    }

    private static void handleLogin(String[] parts, String serverHost, int tcpPort, int socketTimeout) {
        try {
            if (parts.length != 3) {
                System.out.println("Errore: login <username> <password>");
                return;
            }

            String username = parts[1];
            String password = parts[2];

            if (username.trim().isEmpty() || password.trim().isEmpty()) {
                System.out.println("Username e password non possono essere vuoti");
                return;
            }

            if (tcpSocket != null && !tcpSocket.isClosed()) {
                System.out.println("Già loggato. Effettuare logout prima");
                return;
            }

            tcpSocket = new Socket(serverHost, tcpPort);
            tcpSocket.setSoTimeout(socketTimeout);
            in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
            out = new PrintWriter(tcpSocket.getOutputStream(), true);

            int actualUdpPort = startUDPListener(username);
            if (actualUdpPort == -1) {
                System.err.println("Impossibile avviare listener UDP");
                closeConnection();
                return;
            }

            JsonObject loginRequest = new JsonObject();
            loginRequest.addProperty("operation", "login");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("password", password);
            values.addProperty("udpPort", actualUdpPort);
            loginRequest.add("values", values);

            out.println(loginRequest.toString());

            String responseLine = in.readLine();
            if (responseLine == null) {
                System.err.println("Server ha chiuso la connessione");
                stopUDPListener();
                closeConnection();
                return;
            }

            JsonObject response = JsonParser.parseString(responseLine).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();

            boolean loginSuccessful = false;

            switch (responseCode) {
                case 100:
                    System.out.println("Login effettuato come: " + username);
                    currentLoggedUsername = username;
                    loginSuccessful = true;
                    break;
                case 101:
                    System.out.println("Errore: credenziali non valide");
                    break;
                case 102:
                    System.out.println("Errore: utente già connesso");
                    break;
                case 103:
                    System.out.println("Errore interno del server");
                    break;
                default:
                    System.out.println("Errore login (codice " + responseCode + ")");
                    break;
            }

            if (!loginSuccessful) {
                stopUDPListener();
                closeConnection();
                return;
            }

            startMulticastListener();
            startUDPThread();

        } catch (Exception e) {
            System.err.println("[Client] Errore login: " + e.getMessage());
            stopUDPListener();
            closeConnection();
        }
    }

    private static int startUDPListener(String username) {
        try {
            udpListener = new UDPNotificationListener(udpNotificationPort, username);
            udpListener.start();
            return udpListener.getActualPort();
        } catch (Exception e) {
            System.err.println("[Client] Errore UDP: " + e.getMessage());
            return -1;
        }
    }

    private static void startUDPThread() {
        if (udpListener != null && currentLoggedUsername != null) {
            udpListenerThread = new Thread(udpListener, "UDP-Listener");
            udpListenerThread.setDaemon(true);
            udpListenerThread.start();
        }
    }

    private static void stopUDPListener() {
        if (udpListener != null) {
            udpListener.stop();
            if (udpListenerThread != null) {
                udpListenerThread.interrupt();
            }
            udpListener = null;
            udpListenerThread = null;
        }
    }

    private static void closeConnection() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (tcpSocket != null) tcpSocket.close();
            tcpSocket = null;
            in = null;
            out = null;
        } catch (IOException e) {
            System.err.println("[Client] Errore chiusura: " + e.getMessage());
        }
    }

    private static void handleLogout() {
        try {
            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Nessun utente loggato");
                return;
            }

            JsonObject logoutRequest = new JsonObject();
            logoutRequest.addProperty("operation", "logout");
            logoutRequest.add("values", new JsonObject());

            out.println(logoutRequest.toString());

            String responseLine = in.readLine();
            if (responseLine == null) {
                System.err.println("Server ha chiuso la connessione");
                cleanup();
                return;
            }

            JsonObject response = JsonParser.parseString(responseLine).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();

            switch (responseCode) {
                case 100:
                    System.out.println("Logout effettuato");
                    break;
                case 101:
                    System.out.println("Errore logout");
                    break;
                default:
                    System.out.println("Risposta sconosciuta (codice " + responseCode + ")");
                    break;
            }

            cleanup();

        } catch (Exception e) {
            System.err.println("[Client] Errore logout: " + e.getMessage());
            cleanup();
        }
    }

    private static void cleanup() {
        stopMulticastListener();
        stopUDPListener();
        closeConnection();
        currentLoggedUsername = null;
    }

    private static void handleUpdateCredentials(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println("Errore: updateCredentials <username> <old_pwd> <new_pwd>");
                return;
            }

            String username = parts[1];
            String oldPassword = parts[2];
            String newPassword = parts[3];

            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "updateCredentials");

            JsonObject values = new JsonObject();
            values.addProperty("username", username);
            values.addProperty("old_password", oldPassword);
            values.addProperty("new_password", newPassword);
            request.add("values", values);

            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();

            switch (responseCode) {
                case 100:
                    System.out.println("Password aggiornata!");
                    break;
                case 101:
                case 102:
                case 103:
                case 104:
                case 105:
                    String msg = response.has("errorMessage") ? response.get("errorMessage").getAsString() : "";
                    System.out.println("Errore: " + msg);
                    break;
                default:
                    System.out.println("Errore sconosciuto (codice " + responseCode + ")");
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
        }
    }

    private static void handleInsertLimitOrder(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println("Errore: insertLimitOrder <bid/ask> <size> <price>");
                return;
            }

            String type = parts[1].toLowerCase();
            int size, price;

            try {
                size = Integer.parseInt(parts[2]);
                price = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                System.out.println("Size e price devono essere numeri");
                return;
            }

            if (!type.equals("bid") && !type.equals("ask")) {
                System.out.println("Type deve essere 'bid' o 'ask'");
                return;
            }

            if (size <= 0 || price <= 0) {
                System.out.println("Size e price devono essere positivi");
                return;
            }

            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "insertLimitOrder");

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            values.addProperty("price", price);
            request.add("values", values);

            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("Errore inserimento ordine");
                } else {
                    System.out.println("Limit Order creato: ID=" + orderId);
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
        }
    }

    private static void handleInsertMarketOrder(String[] parts) {
        try {
            if (parts.length != 3) {
                System.out.println("Errore: insertMarketOrder <bid/ask> <size>");
                return;
            }

            String type = parts[1].toLowerCase();
            int size;

            try {
                size = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                System.out.println("Size deve essere un numero");
                return;
            }

            if (!type.equals("bid") && !type.equals("ask")) {
                System.out.println("Type deve essere 'bid' o 'ask'");
                return;
            }

            if (size <= 0) {
                System.out.println("Size deve essere positivo");
                return;
            }

            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "insertMarketOrder");

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            request.add("values", values);

            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("Market Order rifiutato");
                } else {
                    System.out.println("Market Order eseguito: ID=" + orderId);
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
        }
    }

    private static void handleInsertStopOrder(String[] parts) {
        try {
            if (parts.length != 4) {
                System.out.println("Errore: insertStopOrder <bid/ask> <size> <stopPrice>");
                return;
            }

            String type = parts[1].toLowerCase();
            int size, stopPrice;

            try {
                size = Integer.parseInt(parts[2]);
                stopPrice = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                System.out.println("Size e stopPrice devono essere numeri");
                return;
            }

            if (!type.equals("bid") && !type.equals("ask")) {
                System.out.println("Type deve essere 'bid' o 'ask'");
                return;
            }

            if (size <= 0 || stopPrice <= 0) {
                System.out.println("Size e stopPrice devono essere positivi");
                return;
            }

            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "insertStopOrder");

            JsonObject values = new JsonObject();
            values.addProperty("type", type);
            values.addProperty("size", size);
            values.addProperty("price", stopPrice);
            request.add("values", values);

            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            if (response.has("orderId")) {
                int orderId = response.get("orderId").getAsInt();
                if (orderId == -1) {
                    System.out.println("Errore Stop Order");
                } else {
                    System.out.println("Stop Order creato: ID=" + orderId);
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
        }
    }

    private static void handleCancelOrder(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println("Errore: cancelOrder <orderId>");
                return;
            }

            int orderId;
            try {
                orderId = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                System.out.println("OrderId deve essere un numero");
                return;
            }

            if (orderId <= 0) {
                System.out.println("OrderId deve essere positivo");
                return;
            }

            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "cancelOrder");

            JsonObject values = new JsonObject();
            values.addProperty("orderId", orderId);
            request.add("values", values);

            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            if (response.has("response")) {
                int responseCode = response.get("response").getAsInt();
                switch (responseCode) {
                    case 100:
                        System.out.println("Ordine " + orderId + " cancellato");
                        break;
                    case 101:
                        System.out.println("Errore: ordine non trovato o non cancellabile");
                        break;
                    default:
                        System.out.println("Errore cancellazione (codice " + responseCode + ")");
                }
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
        }
    }

    private static void handleGetPriceHistory(String[] parts) {
        try {
            if (parts.length != 2) {
                System.out.println("Errore: getPriceHistory <MMYYYY>");
                return;
            }

            String month = parts[1];

            if (month.length() != 6) {
                System.out.println("Formato non valido. Usare MMYYYY");
                return;
            }

            if (tcpSocket == null || tcpSocket.isClosed()) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "getPriceHistory");

            JsonObject values = new JsonObject();
            values.addProperty("month", month);
            request.add("values", values);

            out.println(request.toString());

            String responseStr = in.readLine();
            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();

            if (response.has("error")) {
                System.out.println("Errore: " + response.get("error").getAsString());
                return;
            }

            if (!response.has("priceHistory")) {
                System.out.println("Risposta non valida");
                return;
            }

            displayPriceHistory(response);

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
        }
    }

    private static void displayPriceHistory(JsonObject response) {
        String month = response.get("month").getAsString();
        int totalDays = response.get("totalDays").getAsInt();
        JsonArray historyData = response.getAsJsonArray("priceHistory");

        System.out.println("\n" + repeat("=", 80));
        System.out.println("STORICO PREZZI - " + month);
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

    private static void handleRegisterPriceAlert(String[] parts) {
        try {
            if (tcpSocket == null || tcpSocket.isClosed() || currentLoggedUsername == null) {
                System.out.println("Errore: effettuare login prima");
                return;
            }

            if (parts.length != 2) {
                System.out.println("Errore: registerPriceAlert <soglia>");
                return;
            }

            int thresholdPrice;
            try {
                thresholdPrice = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                System.out.println("Soglia deve essere un numero");
                return;
            }

            if (thresholdPrice <= 0) {
                System.out.println("Soglia deve essere maggiore di zero");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("operation", "registerPriceAlert");

            JsonObject values = new JsonObject();
            values.addProperty("thresholdPrice", thresholdPrice);
            request.add("values", values);

            out.println(request.toString());
            String responseStr = in.readLine();

            if (responseStr == null) {
                System.out.println("Connessione persa");
                return;
            }

            JsonObject response = JsonParser.parseString(responseStr).getAsJsonObject();
            int responseCode = response.get("response").getAsInt();

            switch (responseCode) {
                case 100:
                    System.out.println("Notifica prezzo registrata: " + formatPrice(thresholdPrice));
                    break;
                case 101:
                    System.out.println("Errore: non loggato");
                    break;
                case 103:
                    System.out.println("Errore: parametri non validi");
                    break;
                default:
                    System.out.println("Errore (codice " + responseCode + ")");
                    break;
            }

        } catch (Exception e) {
            System.err.println("[Client] Errore: " + e.getMessage());
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
                        Thread.currentThread().interrupt();
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
    }
}
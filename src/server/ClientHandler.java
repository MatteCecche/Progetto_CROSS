package server;

import server.utility.ResponseBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import server.utility.UserManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Gestisce la comunicazione con un singolo client TCP del sistema CROSS
 * Ogni istanza viene eseguita da un thread diverso preso dal pool del server
 * - Gestione del protocollo di comunicazione JSON con il client
 * - Implementazione delle operazioni di autenticazione e gestione account
 * - Sistema di trading completo con OrderManager integration
 * - Registrazione notifiche prezzo multicast
 * - Sincronizzazione con la mappa globale socket-utente per stato login
 */
public class ClientHandler implements Runnable {

    // Socket TCP per la comunicazione con il client specifico
    private Socket clientSocket;

    // Stream di input per ricevere messaggi JSON dal client
    private BufferedReader in;

    // Stream di output per inviare risposte JSON al client
    private PrintWriter out;

    // Gson per parsing e serializzazione JSON
    private Gson gson;

    // Riferimento alla mappa condivisa socket -> username
    private ConcurrentHashMap<Socket, String> socketUserMap;

    // Salva l'ultimo username loggato per il cleanup
    private String lastLoggedUsername = null;

    //Configura tutte le risorse necessarie per la gestione del client
    public ClientHandler(Socket clientSocket, ConcurrentHashMap<Socket, String> socketUserMap) {
        this.clientSocket = clientSocket;
        this.gson = new Gson();
        this.socketUserMap = socketUserMap;
    }

    public void run() {
        try {
            // Inizializza gli stream di comunicazione
            setupStreams();

            // Loop principale di gestione messaggi JSON
            handleClientMessages();

        } catch (SocketTimeoutException e) {
            System.out.println("[ClientHandler] Timeout client: " +
                    clientSocket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore comunicazione client: " +
                    e.getMessage());
        } finally {
            cleanup();
        }
    }

    //Inizializza gli stream di input/output per la comunicazione JSON
    private void setupStreams() throws IOException {
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new PrintWriter(clientSocket.getOutputStream(), true);
    }

    //Loop principale che gestisce i messaggi JSON dal client
    private void handleClientMessages() throws IOException {
        String messageJson;

        while ((messageJson = in.readLine()) != null) {
            try {
                // Parsing del messaggio JSON ricevuto
                JsonObject request = JsonParser.parseString(messageJson).getAsJsonObject();

                if (!request.has("operation")) {
                    sendErrorResponse(103, "Messaggio JSON non valido: manca campo operation");
                    continue;
                }

                String operation = request.get("operation").getAsString();

                // Salva username per il caso logout
                String userBeforeOperation = getClientInfo();

                // Dispatch dell'operazione al handler specifico
                JsonObject response = processOperation(operation, request);

                if ("logout".equals(operation)) {
                    System.out.println("[ClientHandler] Operazione " + operation +
                            " processata per " + userBeforeOperation);
                } else {
                    System.out.println("[ClientHandler] Operazione " + operation +
                            " processata per " + getClientInfo());
                }

                // Invio della risposta JSON al client
                sendResponse(response);

            } catch (JsonSyntaxException e) {
                System.err.println("[ClientHandler] JSON malformato: " + e.getMessage());
                sendErrorResponse(103, "Formato JSON non valido");
            } catch (Exception e) {
                System.err.println("[ClientHandler] Errore processing operazione: " +
                        e.getMessage());
                sendErrorResponse(103, "Errore interno server");
            }
        }
    }

    //Processa le operazioni ricevute dal client
    private JsonObject processOperation(String operation, JsonObject request) {
        switch (operation) {
            case "login":
                return handleLogin(request);
            case "logout":
                return handleLogout(request);
            case "updateCredentials":
                return handleUpdateCredentials(request);
            case "insertLimitOrder":
                return handleInsertLimitOrder(request);
            case "insertMarketOrder":
                return handleInsertMarketOrder(request);
            case "insertStopOrder":
                return handleInsertStopOrder(request);
            case "cancelOrder":
                return handleCancelOrder(request);
            case "getPriceHistory":
                return handleGetPriceHistory(request);
            case "registerPriceAlert":
                return handleRegisterPriceAlert(request);
            default:
                return createErrorResponse(103, "Operazione non supportata: " + operation);
        }
    }

    //Gestisce il login
    private JsonObject handleLogin(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createErrorResponse(103, "Messaggio login non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String password = getStringValue(values, "password");

            // Validazione parametri tramite UserManager
            if (!UserManager.validateLoginParams(username, password)) {
                return createErrorResponse(103, "Parametri login non validi");
            }

            // Controllo se l'utente è già loggato
            if (isUserAlreadyLoggedIn(username)) {
                return ResponseBuilder.Login.userAlreadyLogged();
            }

            // Verifica credenziali tramite UserManager
            if (!UserManager.validateCredentials(username, password)) {
                return ResponseBuilder.Login.wrongCredentials();
            }

            // Login completato
            socketUserMap.put(clientSocket, username);

            // Registra client per notifiche UDP trade
            if (values.has("udpPort")) {
                try {
                    int udpPort = values.get("udpPort").getAsInt();
                    InetAddress clientIP = clientSocket.getInetAddress();
                    UDPNotificationService.registerClient(
                            username,
                            new InetSocketAddress(clientIP, udpPort)
                    );
                } catch (Exception e) {
                    System.err.println("[ClientHandler] Errore registrazione UDP: " + e.getMessage());
                }
            }

            System.out.println("[ClientHandler] Login successful: " + username);

            return ResponseBuilder.Login.success();

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante login: " + e.getMessage());
            return createErrorResponse(103, "Errore interno durante login");
        }
    }

    //Gestisce il logout
    private JsonObject handleLogout(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);

            if (loggedUsername == null) {
                return createErrorResponse(101, "Nessun utente loggato su questa connessione");
            }

            System.out.println("[ClientHandler] Processando logout per: " + loggedUsername);

            // Rimuovi dalla mappa
            socketUserMap.remove(clientSocket);

            // Rimuovi utente dalle notifiche multicast prima del logout
            PriceNotificationService.unregisterUser(loggedUsername);

            // Rimuovi dalle notifiche UDP
            UDPNotificationService.unregisterClient(loggedUsername);

            System.out.println("[ClientHandler] Logout successful: " + loggedUsername);
            return ResponseBuilder.Logout.success();

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante logout: " + e.getMessage());
            return createErrorResponse(101, "Errore interno durante logout");
        }
    }

    //Gestisce updateCredentials
    private JsonObject handleUpdateCredentials(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createErrorResponse(103, "Messaggio non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String oldPassword = getStringValue(values, "old_password");
            String newPassword = getStringValue(values, "new_password");

            // Controllo se l'utente è attualmente loggato
            if (isUserAlreadyLoggedIn(username)) {
                return ResponseBuilder.UpdateCredentials.userAlreadyLogged();
            }

            // Validazione nuova password
            if (!UserManager.isValidPassword(newPassword)) {
                return ResponseBuilder.UpdateCredentials.invalidPassword();
            }

            // Controllo che nuova password sia diversa dalla vecchia
            if (oldPassword.equals(newPassword)) {
                return ResponseBuilder.UpdateCredentials.passwordEqualToOld();
            }

            // Aggiornamento tramite UserManager
            int success = UserManager.updatePassword(username, oldPassword, newPassword);
            if (success == 100) {
                System.out.println("[ClientHandler] Password aggiornata per utente: " + username);
                return ResponseBuilder.UpdateCredentials.success();
            } else {
                return ResponseBuilder.UpdateCredentials.usernameNotFound();
            }

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore aggiornamento credenziali: " + e.getMessage());
            return ResponseBuilder.UpdateCredentials.otherError("Errore interno durante aggiornamento");
        }
    }

    //Gestisce insertLimitOrder
    private JsonObject handleInsertLimitOrder(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return ResponseBuilder.Logout.userNotLogged();
            }
            if (!request.has("values")) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            int size = getIntValue(values, "size");
            int price = getIntValue(values, "price");

            // Chiamata a OrderManager per inserimento
            int orderId = OrderManager.insertLimitOrder(loggedUsername, type, size, price);

            if (orderId == -1) {
                return ResponseBuilder.TradingOrder.error();
            }

            // Risposta success con orderId
            return ResponseBuilder.TradingOrder.success(orderId);

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore insertLimitOrder: " + e.getMessage());
            return ResponseBuilder.TradingOrder.error();
        }
    }

    //Gestisce insertMarketOrder
    private JsonObject handleInsertMarketOrder(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return ResponseBuilder.TradingOrder.error();
            }
            if (!request.has("values")) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            int size = getIntValue(values, "size");

            // Chiamata a OrderManager per inserimento
            int orderId = OrderManager.insertMarketOrder(loggedUsername, type, size);

            if (orderId == -1) {
                return ResponseBuilder.TradingOrder.error();
            }

            // Risposta success con orderId
            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore insertMarketOrder: " + e.getMessage());
            return ResponseBuilder.TradingOrder.error();
        }
    }

    //Gestisce insertStopOrder
    private JsonObject handleInsertStopOrder(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return ResponseBuilder.TradingOrder.error();
            }
            if (!request.has("values")) {
                return ResponseBuilder.TradingOrder.error();
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            int size = getIntValue(values, "size");
            int stopPrice = getIntValue(values, "price");

            // Chiamata a OrderManager per inserimento
            int orderId = OrderManager.insertStopOrder(loggedUsername, type, size, stopPrice);

            if (orderId == -1) {
                return ResponseBuilder.TradingOrder.error();
            }

            // Risposta success con orderId
            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore insertStopOrder: " + e.getMessage());
            return ResponseBuilder.TradingOrder.error();
        }
    }

    //Gestisce cancelOrder
    private JsonObject handleCancelOrder(JsonObject request) {
        try {
            // Controllo autenticazione
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato");
            }
            if (!request.has("values")) {
                return createErrorResponse(101, "Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            int orderId = getIntValue(values, "orderId");

            // Chiamata a OrderManager per cancellazione
            int result = OrderManager.cancelOrder(loggedUsername, orderId);

            if (result == 100) {
                return ResponseBuilder.CancelOrder.success();
            } else {
                return ResponseBuilder.CancelOrder.orderError("Impossibile cancellare ordine");
            }

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore cancelOrder: " + e.getMessage());
            return createErrorResponse(101, "Errore interno server");
        }
    }

    //Gestisce getPriceHistory
    private JsonObject handleGetPriceHistory(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato");
            }
            if (!request.has("values")) {
                return createErrorResponse(103, "Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            if (!values.has("month")) {
                return createErrorResponse(103, "Formato richiesta non valido: manca parametro month");
            }

            String month = getStringValue(values, "month");
            if (month.isEmpty()) {
                return createErrorResponse(103, "Parametro month non può essere vuoto");
            }

            System.out.println("[ClientHandler] Richiesta storico prezzi per mese: " + month +
                    " da utente: " + loggedUsername);

            // Chiamata a OrderManager per ottenere i dati storici
            JsonObject historyData = OrderManager.getPriceHistory(month);

            // Controlla se c'è stato un errore
            if (historyData.has("error")) {
                return createErrorResponse(103, historyData.get("error").getAsString());
            }

            System.out.println("[ClientHandler] Storico prezzi generato per " + month +
                    ": " + historyData.get("totalDays").getAsInt() + " giorni");

            return historyData;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore getPriceHistory: " + e.getMessage());
            return createErrorResponse(103, "Errore interno del server durante generazione storico");
        }
    }

    //Gestisce la registrazione di un utente per notifiche di soglia prezzo
    private JsonObject handleRegisterPriceAlert(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato - effettuare login prima di registrare notifiche");
            }
            if (!request.has("values")) {
                return createErrorResponse(103, "Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            if (!values.has("thresholdPrice")) {
                return createErrorResponse(103, "Formato richiesta non valido: manca thresholdPrice");
            }

            // Estrazione e validazione soglia prezzo
            int thresholdPrice;
            try {
                thresholdPrice = values.get("thresholdPrice").getAsInt();
            } catch (Exception e) {
                return createErrorResponse(103, "thresholdPrice deve essere un numero intero");
            }

            if (thresholdPrice <= 0) {
                return createErrorResponse(103, "thresholdPrice deve essere maggiore di zero");
            }

            // Controllo soglia maggiore del prezzo corrente
            int currentPrice = OrderManager.getCurrentMarketPrice();
            if (thresholdPrice <= currentPrice) {
                return createErrorResponse(103, "thresholdPrice (" + formatPrice(thresholdPrice) +
                        ") deve essere maggiore del prezzo corrente (" + formatPrice(currentPrice) + ")");
            }

            // Registrazione utente per notifiche multicast
            PriceNotificationService.registerUserForPriceNotifications(loggedUsername, thresholdPrice);

            // Risposta di successo con info multicast per il client
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("message", "Registrazione notifiche prezzo completata");

            // Aggiungi info multicast per il client
            JsonObject multicastInfo = PriceNotificationService.getMulticastInfo();
            response.add("multicastInfo", multicastInfo);

            System.out.println("[ClientHandler] Utente " + loggedUsername +
                    " registrato per notifiche prezzo > " + formatPrice(thresholdPrice) + " USD");

            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore registrazione notifiche prezzo: " + e.getMessage());
            return createErrorResponse(103, "Errore interno durante registrazione notifiche");
        }
    }

    //Controlla se un utente è già loggato
    private boolean isUserAlreadyLoggedIn(String username) {
        return socketUserMap.containsValue(username);
    }

    //Ottiene informazioni sul client
    private String getClientInfo() {
        String username = socketUserMap.get(clientSocket);
        if (username != null) {
            return username;
        } else {
            return "anonymous";
        }
    }

    //Estrae valore stringa da JsonObject con gestione errori
    private String getStringValue(JsonObject json, String key) {
        try {
            if (json.has(key)) {
                return json.get(key).getAsString();
            } else {
                return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    //Estrae valore intero da JsonObject con gestione errori
    private int getIntValue(JsonObject json, String key) {
        try {
            if (json.has(key)) {
                return json.get(key).getAsInt();
            } else {
                return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    //Formatta prezzo in millesimi
    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    //Crea risposta di successo
    private JsonObject createSuccessResponse(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }

    //Crea oggetto risposta di errore
    private JsonObject createErrorResponse(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }

    //Invia risposta JSON al client
    private void sendResponse(JsonObject response) {
        out.println(response.toString());
    }

    //Invia risposta di errore rapida
    private void sendErrorResponse(int code, String message) {
        sendResponse(createErrorResponse(code, message));
    }

    //Rimuove utente dalla mappa degli utenti loggati e dalle notifiche multicast
    private void cleanup() {
        try {
            // Salva il nome utente PRIMA di rimuoverlo dalla mappa
            String loggedUsername = socketUserMap.get(clientSocket);
            String userInfo;
            if (loggedUsername != null) {
                userInfo = loggedUsername;
                PriceNotificationService.unregisterUser(loggedUsername);
                UDPNotificationService.unregisterClient(loggedUsername);
            } else if (lastLoggedUsername != null) {
            // logout esplicito fatto
            userInfo = lastLoggedUsername;
        } else {
            // Nessun login mai fatto
            userInfo = "anonymous";
        }

            // Rimuovi utente dalle notifiche multicast se era loggato
            if (loggedUsername != null) {
                PriceNotificationService.unregisterUser(loggedUsername);
                UDPNotificationService.unregisterClient(loggedUsername);
            }

            // Rimuovi dalla mappa degli utenti loggati
            socketUserMap.remove(clientSocket);

            // Chiudi stream
            if (in != null) in.close();
            if (out != null) out.close();

            // Chiudi socket se non già chiuso
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }

            System.out.println("[ClientHandler] Client disconnesso: " + userInfo);

        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore durante cleanup: " + e.getMessage());
        }
    }
}
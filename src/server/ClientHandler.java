package server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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

/**
 * Gestisce la comunicazione con un singolo client TCP del sistema CROSS
 * Ogni istanza viene eseguita da un thread diverso preso dal pool del server
 * - Gestione del protocollo di comunicazione JSON con il client
 * - Implementazione delle operazioni di autenticazione e gestione account
 * - Sistema di trading completo con OrderManager integration
 * - Registrazione notifiche prezzo multicast (vecchio ordinamento)
 * - Sincronizzazione con la mappa globale socket-utente per stato login
 * - Gestione robusta degli errori e cleanup delle risorse
 *
 * Utilizza UserManager per tutte le operazioni sui dati utenti,
 * garantendo centralizzazione e consistenza della logica di persistenza.
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
    // Utilizzata per tracciare quali utenti sono attualmente loggati
    private ConcurrentHashMap<Socket, String> socketUserMap;

    /**
     * Costruttore - inizializza handler per un client specifico
     * Configura tutte le risorse necessarie per la gestione del client
     * La mappa degli utenti loggati è condivisa tra tutti i ClientHandler
     *
     * @param clientSocket socket della connessione TCP stabilita con il client
     * @param socketUserMap mappa condivisa thread-safe socket -> username per tracking login
     */
    public ClientHandler(Socket clientSocket, ConcurrentHashMap<Socket, String> socketUserMap) {
        this.clientSocket = clientSocket;
        this.gson = new Gson();
        this.socketUserMap = socketUserMap;
    }

    /**
     * Main del thread - gestisce le comunicazioni con il client
     * 1. Setup degli stream di comunicazione
     * 2. Loop di gestione messaggi JSON
     * 3. Gestione timeout e disconnessioni
     * 4. Cleanup garantito delle risorse
     *
     * Questo metodo viene eseguito dal thread pool del server
     */
    @Override
    public void run() {
        try {
            // Inizializza gli stream di comunicazione
            setupStreams();

            System.out.println("[ClientHandler] Client connesso: " +
                    clientSocket.getRemoteSocketAddress());

            // Loop principale di gestione messaggi JSON
            handleClientMessages();

        } catch (SocketTimeoutException e) {
            System.out.println("[ClientHandler] Timeout client: " +
                    clientSocket.getRemoteSocketAddress());
        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore comunicazione client: " +
                    e.getMessage());
        } finally {
            // Cleanup garantito delle risorse anche in caso di eccezioni
            cleanup();
        }
    }

    /**
     * Inizializza gli stream di input/output per la comunicazione JSON
     * Configura BufferedReader per lettura efficiente delle righe JSON
     * e PrintWriter con autoflush per invio immediato delle risposte
     *
     * @throws IOException se errori nella creazione degli stream dal socket
     */
    private void setupStreams() throws IOException {
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new PrintWriter(clientSocket.getOutputStream(), true);
    }

    /**
     * Loop principale che gestisce i messaggi JSON dal client
     *
     * @throws IOException se errori nella comunicazione con il client
     */
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
                System.out.println("[ClientHandler] Operazione ricevuta: " + operation +
                        " da " + getClientInfo());

                // Dispatch dell'operazione al handler specifico
                JsonObject response = processOperation(operation, request);

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

    /**
     * Processa le operazioni ricevute dal client
     *
     * @param operation tipo di operazione richiesta dal client
     * @param request oggetto JSON completo della richiesta con parametri
     * @return JsonObject contenente la risposta da inviare al client
     */
    private JsonObject processOperation(String operation, JsonObject request) {
        // Switch compatibile Java 8 - ESTESO con operazioni trading e multicast
        switch (operation) {
            // === OPERAZIONI AUTENTICAZIONE ===
            case "login":
                return handleLogin(request);
            case "logout":
                return handleLogout(request);
            case "updateCredentials":
                return handleUpdateCredentials(request);

            // === OPERAZIONI TRADING ===
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

            // === NOTIFICHE PREZZO MULTICAST (VECCHIO ORDINAMENTO) ===
            case "registerPriceAlert":
                return handleRegisterPriceAlert(request);

            default:
                return createErrorResponse(103, "Operazione non supportata: " + operation);
        }
    }

    // === OPERAZIONI AUTENTICAZIONE ===

    /**
     * Gestisce il login del client
     *
     * @param request oggetto JSON contenente username e password nel campo "values"
     * @return JsonObject con codice risposta e messaggio secondo ALLEGATO 1
     */
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

            // Controllo se l'utente è già loggato (usando la mappa condivisa)
            if (isUserAlreadyLoggedIn(username)) {
                return createErrorResponse(102, "Utente già loggato");
            }

            // Verifica credenziali tramite UserManager
            if (!UserManager.validateCredentials(username, password)) {
                return createErrorResponse(101, "Credenziali non valide");
            }

            // Login successful - registra nella mappa condivisa
            socketUserMap.put(clientSocket, username);
            System.out.println("[ClientHandler] Login successful: " + username);

            return createSuccessResponse(100, "Login effettuato con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante login: " + e.getMessage());
            return createErrorResponse(103, "Errore interno durante login");
        }
    }

    /**
     * Gestisce il logout del client
     *
     * @param request oggetto JSON della richiesta logout
     * @return JsonObject con esito dell'operazione
     */
    private JsonObject handleLogout(JsonObject request) {
        try {
            String loggedUsername = socketUserMap.get(clientSocket);

            if (loggedUsername == null) {
                return createErrorResponse(101, "Nessun utente loggato su questa connessione");
            }

            // Rimuovi utente dalle notifiche multicast prima del logout
            PriceNotificationService.unregisterUser(loggedUsername);

            // Rimuovi dalla mappa degli utenti loggati
            socketUserMap.remove(clientSocket);

            System.out.println("[ClientHandler] Logout successful: " + loggedUsername);
            return createSuccessResponse(100, "Logout effettuato con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante logout: " + e.getMessage());
            return createErrorResponse(101, "Errore interno durante logout");
        }
    }

    /**
     * Gestisce l'aggiornamento delle credenziali utente
     *
     * @param request oggetto JSON con username, old_password, new_password
     * @return JsonObject con esito secondo ALLEGATO 1
     */
    private JsonObject handleUpdateCredentials(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createErrorResponse(103, "Messaggio non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String oldPassword = getStringValue(values, "old_password");
            String newPassword = getStringValue(values, "new_password");

            // Controllo se l'utente è attualmente loggato (non può cambiare password)
            if (isUserAlreadyLoggedIn(username)) {
                return createErrorResponse(104, "Impossibile aggiornare password: utente attualmente loggato");
            }

            // Validazione nuova password
            if (!UserManager.isValidPassword(newPassword)) {
                return createErrorResponse(101, "Nuova password non valida");
            }

            // Controllo che nuova password sia diversa dalla vecchia
            if (oldPassword.equals(newPassword)) {
                return createErrorResponse(103, "La nuova password deve essere diversa da quella attuale");
            }

            // Aggiornamento tramite UserManager
            boolean success = UserManager.updatePassword(username, oldPassword, newPassword);
            if (success) {
                System.out.println("[ClientHandler] Password aggiornata per utente: " + username);
                return createSuccessResponse(100, "Password aggiornata con successo");
            } else {
                return createErrorResponse(102, "Username non esistente o password attuale errata");
            }

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore aggiornamento credenziali: " + e.getMessage());
            return createErrorResponse(105, "Errore interno durante aggiornamento");
        }
    }

    // === OPERAZIONI TRADING ===

    /**
     * Gestisce inserimento Limit Order
     *
     * @param request JSON con type, size, price secondo ALLEGATO 1
     * @return JsonObject con orderId o errore
     */
    private JsonObject handleInsertLimitOrder(JsonObject request) {
        try {
            // Controllo autenticazione
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createOrderErrorResponse("Utente non loggato");
            }

            // Validazione formato richiesta
            if (!request.has("values")) {
                return createOrderErrorResponse("Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            int size = getIntValue(values, "size");
            int price = getIntValue(values, "price");

            // Chiamata a OrderManager per inserimento
            int orderId = OrderManager.insertLimitOrder(loggedUsername, type, size, price);

            if (orderId == -1) {
                return createOrderErrorResponse("Errore inserimento Limit Order");
            }

            // Risposta success con orderId
            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore insertLimitOrder: " + e.getMessage());
            return createOrderErrorResponse("Errore interno server");
        }
    }

    /**
     * Gestisce inserimento Market Order
     *
     * @param request JSON con type, size secondo ALLEGATO 1
     * @return JsonObject con orderId o errore
     */
    private JsonObject handleInsertMarketOrder(JsonObject request) {
        try {
            // Controllo autenticazione
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createOrderErrorResponse("Utente non loggato");
            }

            // Validazione formato richiesta
            if (!request.has("values")) {
                return createOrderErrorResponse("Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            int size = getIntValue(values, "size");

            // Chiamata a OrderManager per inserimento
            int orderId = OrderManager.insertMarketOrder(loggedUsername, type, size);

            if (orderId == -1) {
                return createOrderErrorResponse("Errore inserimento Market Order");
            }

            // Risposta success con orderId
            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore insertMarketOrder: " + e.getMessage());
            return createOrderErrorResponse("Errore interno server");
        }
    }

    /**
     * Gestisce inserimento Stop Order
     *
     * @param request JSON con type, size, price (stopPrice) secondo ALLEGATO 1
     * @return JsonObject con orderId o errore
     */
    private JsonObject handleInsertStopOrder(JsonObject request) {
        try {
            // Controllo autenticazione
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createOrderErrorResponse("Utente non loggato");
            }

            // Validazione formato richiesta
            if (!request.has("values")) {
                return createOrderErrorResponse("Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            int size = getIntValue(values, "size");
            int stopPrice = getIntValue(values, "price"); // stopPrice secondo ALLEGATO 1

            // Chiamata a OrderManager per inserimento
            int orderId = OrderManager.insertStopOrder(loggedUsername, type, size, stopPrice);

            if (orderId == -1) {
                return createOrderErrorResponse("Errore inserimento Stop Order");
            }

            // Risposta success con orderId
            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore insertStopOrder: " + e.getMessage());
            return createOrderErrorResponse("Errore interno server");
        }
    }

    /**
     * Gestisce cancellazione ordine
     *
     * @param request JSON con orderId secondo ALLEGATO 1
     * @return JsonObject con codici risposta 100/101
     */
    private JsonObject handleCancelOrder(JsonObject request) {
        try {
            // Controllo autenticazione
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato");
            }

            // Validazione formato richiesta
            if (!request.has("values")) {
                return createErrorResponse(101, "Formato richiesta non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            int orderId = getIntValue(values, "orderId");

            // Chiamata a OrderManager per cancellazione
            int result = OrderManager.cancelOrder(loggedUsername, orderId);

            if (result == 100) {
                return createSuccessResponse(100, "Ordine cancellato con successo");
            } else {
                return createErrorResponse(101, "Impossibile cancellare ordine");
            }

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore cancelOrder: " + e.getMessage());
            return createErrorResponse(101, "Errore interno server");
        }
    }

    /**
     * Gestisce richiesta di storico prezzi per un mese specifico
     * Formato JSON secondo ALLEGATO 1: month=STRING(MMYYYY)
     *
     * @param request JSON con month in formato MMYYYY
     * @return JsonObject con dati storici OHLC per ogni giorno
     */
    private JsonObject handleGetPriceHistory(JsonObject request) {
        try {
            // Controllo autenticazione
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato");
            }

            // Validazione formato richiesta
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

    // === NOTIFICHE PREZZO MULTICAST (VECCHIO ORDINAMENTO) ===

    /**
     * Gestisce la registrazione di un utente per notifiche di soglia prezzo
     * Operazione specifica per studenti vecchio ordinamento
     *
     * Formato JSON richiesta:
     * {
     *   "operation": "registerPriceAlert",
     *   "values": {
     *     "thresholdPrice": NUMBER  // soglia in millesimi USD
     *   }
     * }
     *
     * @param request oggetto JSON contenente la soglia prezzo desiderata
     * @return JsonObject con esito operazione
     */
    private JsonObject handleRegisterPriceAlert(JsonObject request) {
        try {
            // Controllo che utente sia loggato
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato - effettuare login prima di registrare notifiche");
            }

            // Validazione formato richiesta
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

            // Controllo logico: soglia deve essere maggiore del prezzo corrente
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

    // === UTILITY METHODS ===

    /**
     * Controlla se un utente è già loggato (thread-safe)
     *
     * @param username nome utente da controllare
     * @return true se utente già loggato
     */
    private boolean isUserAlreadyLoggedIn(String username) {
        return socketUserMap.containsValue(username);
    }

    /**
     * Ottiene informazioni sul client per logging
     *
     * @return stringa con info client (username@indirizzo)
     */
    private String getClientInfo() {
        String username = socketUserMap.get(clientSocket);
        if (username != null) {
            return username + "@" + clientSocket.getRemoteSocketAddress();
        } else {
            return "anonymous@" + clientSocket.getRemoteSocketAddress();
        }
    }

    /**
     * Estrae valore stringa da JsonObject con gestione errori
     *
     * @param json oggetto JSON
     * @param key chiave da estrarre
     * @return valore stringa o stringa vuota se errore
     */
    private String getStringValue(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Estrae valore intero da JsonObject con gestione errori
     *
     * @param json oggetto JSON
     * @param key chiave da estrarre
     * @return valore intero o 0 se errore
     */
    private int getIntValue(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Formatta prezzo in millesimi per display user-friendly
     *
     * @param priceInMillis prezzo in millesimi USD
     * @return prezzo formattato (es: "58.000")
     */
    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    // === RESPONSE HELPERS ===

    /**
     * Crea risposta di successo standardizzata
     *
     * @param code codice di risposta
     * @param message messaggio descrittivo
     * @return JsonObject strutturato secondo ALLEGATO 1
     */
    private JsonObject createSuccessResponse(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }

    /**
     * Crea risposta di errore standardizzata
     *
     * @param code codice di errore
     * @param message messaggio di errore
     * @return JsonObject strutturato secondo ALLEGATO 1
     */
    private JsonObject createErrorResponse(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }

    /**
     * Crea risposta di errore per operazioni ordini
     *
     * @param errorMessage messaggio di errore
     * @return JsonObject con orderId = -1 secondo ALLEGATO 1
     */
    private JsonObject createOrderErrorResponse(String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("orderId", -1);
        return response;
    }

    /**
     * Invia risposta JSON al client
     *
     * @param response oggetto JSON da inviare
     */
    private void sendResponse(JsonObject response) {
        out.println(response.toString());
    }

    /**
     * Invia risposta di errore rapida
     *
     * @param code codice di errore
     * @param message messaggio di errore
     */
    private void sendErrorResponse(int code, String message) {
        sendResponse(createErrorResponse(code, message));
    }

    /**
     * Cleanup delle risorse del client
     * Rimuove utente dalla mappa degli utenti loggati e dalle notifiche multicast
     * Chiude stream e socket
     */
    private void cleanup() {
        try {
            // Rimuovi utente dalle notifiche multicast se era loggato
            String loggedUsername = socketUserMap.get(clientSocket);
            if (loggedUsername != null) {
                PriceNotificationService.unregisterUser(loggedUsername);
                System.out.println("[ClientHandler] Cleanup: rimosso " + loggedUsername + " dalle notifiche multicast");
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

            System.out.println("[ClientHandler] Client disconnesso: " + getClientInfo());

        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore durante cleanup: " + e.getMessage());
        }
    }
}
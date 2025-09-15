package server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import server.utility.UserManager;
import com.google.gson.JsonArray;

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

                // Dispatch dell'operazione handler specifico
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
        // Switch compatibile Java 8 - ESTESO con operazioni trading
        switch (operation) {
            case "login":
                return handleLogin(request);
            case "logout":
                return handleLogout(request);
            case "updateCredentials":
                return handleUpdateCredentials(request);

            // ✅ OPERAZIONI TRADING
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

            default:
                return createErrorResponse(103, "Operazione non supportata: " + operation);
        }
    }

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

            // Carica utenti e cerca l'utente nel sistema
            JsonArray users = UserManager.loadUsers();
            JsonObject user = UserManager.findUser(users, username);

            if (user == null) {
                System.out.println("[ClientHandler] Login fallito: utente non esistente - " + username);
                return createErrorResponse(101, "Username/password non corrispondenti");
            }

            // Verifica password
            String storedPassword = user.get("password").getAsString();
            if (!storedPassword.equals(password)) {
                System.out.println("[ClientHandler] Login fallito: password errata - " + username);
                return createErrorResponse(101, "Username/password non corrispondenti");
            }

            // Controlla se utente già loggato su altra connessione
            if (socketUserMap.containsValue(username)) {
                System.out.println("[ClientHandler] Login fallito: utente già loggato - " + username);
                return createErrorResponse(102, "Utente già loggato");
            }

            // Registra associazione socket -> username per tracking stato
            socketUserMap.put(clientSocket, username);

            System.out.println("[ClientHandler] Login successful per: " + username);
            return createSuccessResponse("Login effettuato con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante login: " + e.getMessage());
            return createErrorResponse(103, "Errore interno durante login");
        }
    }

    /**
     * Gestisce il logout del client
     * Implementa disconnessione utente dal sistema rimuovendo
     * l'associazione dalla mappa socket-utente
     *
     * @param request oggetto JSON della richiesta (values vuoto per logout)
     * @return JsonObject con codice risposta e messaggio
     */
    private JsonObject handleLogout(JsonObject request) {
        try {
            // Identifica utente tramite socket nella mappa condivisa
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createErrorResponse(101, "Utente non loggato");
            }

            // Rimuove associazione socket-utente dalla mappa
            socketUserMap.remove(clientSocket);

            System.out.println("[ClientHandler] Logout per: " + username);

            return createSuccessResponse("Logout effettuato per utente: " + username);

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante logout: " + e.getMessage());
            return createErrorResponse(101, "Errore interno durante logout");
        }
    }

    /**
     * Gestisce l'aggiornamento delle credenziali utente
     *
     * @param request oggetto JSON con username, old_password, new_password
     * @return JsonObject con codice risposta secondo specifiche ALLEGATO 1
     */
    private JsonObject handleUpdateCredentials(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createErrorResponse(105, "Messaggio updateCredentials non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String oldPassword = getStringValue(values, "old_password");
            String newPassword = getStringValue(values, "new_password");

            // Validazione parametri base
            if (username == null || oldPassword == null || newPassword == null ||
                    username.trim().isEmpty() || oldPassword.trim().isEmpty() || newPassword.trim().isEmpty()) {
                // Specifica meglio: se new_password è vuota = 101, altrimenti = 105
                if (newPassword == null || newPassword.trim().isEmpty()) {
                    return createErrorResponse(101, "Nuova password non valida");
                }
                return createErrorResponse(105, "Parametri non validi");
            }

            // Controlla che le password siano diverse (codice 103 secondo ALLEGATO 1)
            if (oldPassword.equals(newPassword)) {
                return createErrorResponse(103, "La nuova password non può essere uguale alla precedente");
            }

            // Verifica che utente NON sia attualmente loggato (codice 104 secondo ALLEGATO 1)
            if (socketUserMap.containsValue(username)) {
                return createErrorResponse(104, "Impossibile cambiare password: utente attualmente loggato");
            }

            // Carica utenti e cerca l'utente specificato
            JsonArray users = UserManager.loadUsers();
            JsonObject user = UserManager.findUser(users, username);

            // Username/password mismatch = codice 102 secondo ALLEGATO 1
            if (user == null) {
                return createErrorResponse(102, "Username non esistente");
            }

            // Verifica password attuale fornita
            String storedPassword = user.get("password").getAsString();
            if (!storedPassword.equals(oldPassword)) {
                return createErrorResponse(102, "Password attuale non corretta");
            }

            // Aggiorna password nell'oggetto utente
            user.addProperty("password", newPassword);

            // Salva utenti aggiornati tramite UserManager
            UserManager.saveUsers(users);

            System.out.println("[ClientHandler] Password aggiornata per utente: " + username);
            return createSuccessResponse("Password aggiornata con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante update credenziali: " + e.getMessage());
            return createErrorResponse(105, "Errore interno durante aggiornamento");
        }
    }

    // =============== TRADING OPERATIONS ===============

    /**
     * Gestisce l'inserimento di un Limit Order
     * Formato JSON ALLEGATO 1: type=bid/ask, size=NUMBER, price=NUMBER
     * Risposta: { "orderId": NUMBER } o { "orderId": -1 } per errori
     *
     * @param request oggetto JSON con type, size, price nel campo "values"
     * @return JsonObject con orderId (o -1 se errore)
     */
    private JsonObject handleInsertLimitOrder(JsonObject request) {
        try {
            // Controllo autenticazione utente
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createOrderErrorResponse("Utente non loggato");
            }

            if (!request.has("values")) {
                return createOrderErrorResponse("Messaggio insertLimitOrder non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");

            // Parsing size e price come interi (millesimi secondo specifiche)
            Integer size = getIntegerValue(values, "size");
            Integer price = getIntegerValue(values, "price");

            // Validazione parametri
            if (type == null || size == null || price == null ||
                    (!type.equals("bid") && !type.equals("ask")) || size <= 0 || price <= 0) {
                return createOrderErrorResponse("Parametri Limit Order non validi");
            }

            // Chiamata all'OrderManager per inserimento
            int orderId = OrderManager.insertLimitOrder(username, type, size, price);

            System.out.println("[ClientHandler] Limit Order " + orderId + " inserito per: " + username +
                    " (" + type + " " + formatSize(size) + " BTC @ " + formatPrice(price) + " USD)");

            return createOrderResponse(orderId);

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante insertLimitOrder: " + e.getMessage());
            return createOrderErrorResponse("Errore interno durante inserimento ordine");
        }
    }

    /**
     * Gestisce l'inserimento di un Market Order
     * Formato JSON ALLEGATO 1: type=bid/ask, size=NUMBER
     * Risposta: { "orderId": NUMBER } o { "orderId": -1 } per errori
     *
     * @param request oggetto JSON con type, size nel campo "values"
     * @return JsonObject con orderId (o -1 se errore)
     */
    private JsonObject handleInsertMarketOrder(JsonObject request) {
        try {
            // Controllo autenticazione utente
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createOrderErrorResponse("Utente non loggato");
            }

            if (!request.has("values")) {
                return createOrderErrorResponse("Messaggio insertMarketOrder non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            Integer size = getIntegerValue(values, "size");

            // Validazione parametri
            if (type == null || size == null ||
                    (!type.equals("bid") && !type.equals("ask")) || size <= 0) {
                return createOrderErrorResponse("Parametri Market Order non validi");
            }

            // Chiamata all'OrderManager per esecuzione immediata
            int orderId = OrderManager.insertMarketOrder(username, type, size);

            System.out.println("[ClientHandler] Market Order " + orderId + " eseguito per: " + username +
                    " (" + type + " " + formatSize(size) + " BTC al prezzo di mercato)");

            return createOrderResponse(orderId);

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante insertMarketOrder: " + e.getMessage());
            return createOrderErrorResponse("Errore interno durante inserimento ordine");
        }
    }

    /**
     * Gestisce l'inserimento di un Stop Order
     * Formato JSON ALLEGATO 1: type=bid/ask, size=NUMBER, price=NUMBER (stopPrice)
     * Risposta: { "orderId": NUMBER } o { "orderId": -1 } per errori
     *
     * @param request oggetto JSON con type, size, price nel campo "values"
     * @return JsonObject con orderId (o -1 se errore)
     */
    private JsonObject handleInsertStopOrder(JsonObject request) {
        try {
            // Controllo autenticazione utente
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createOrderErrorResponse("Utente non loggato");
            }

            if (!request.has("values")) {
                return createOrderErrorResponse("Messaggio insertStopOrder non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String type = getStringValue(values, "type");
            Integer size = getIntegerValue(values, "size");
            Integer stopPrice = getIntegerValue(values, "price"); // price field = stopPrice

            // Validazione parametri
            if (type == null || size == null || stopPrice == null ||
                    (!type.equals("bid") && !type.equals("ask")) || size <= 0 || stopPrice <= 0) {
                return createOrderErrorResponse("Parametri Stop Order non validi");
            }

            // Chiamata all'OrderManager per inserimento stop order
            int orderId = OrderManager.insertStopOrder(username, type, size, stopPrice);

            System.out.println("[ClientHandler] Stop Order " + orderId + " inserito per: " + username +
                    " (" + type + " " + formatSize(size) + " BTC @ stop " + formatPrice(stopPrice) + " USD)");

            return createOrderResponse(orderId);

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante insertStopOrder: " + e.getMessage());
            return createOrderErrorResponse("Errore interno durante inserimento ordine");
        }
    }

    /**
     * Gestisce la cancellazione di un ordine
     * Formato JSON ALLEGATO 1: orderId=NUMBER
     * Risposta secondo ALLEGATO 1: { "response": 100/101, "errorMessage": STRING }
     *
     * @param request oggetto JSON con orderId nel campo "values"
     * @return JsonObject con response code secondo ALLEGATO 1
     */
    private JsonObject handleCancelOrder(JsonObject request) {
        try {
            // Controllo autenticazione utente
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createErrorResponse(101, "Utente non loggato");
            }

            if (!request.has("values")) {
                return createErrorResponse(101, "Messaggio cancelOrder non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            Integer orderId = getIntegerValue(values, "orderId");

            // Validazione parametri
            if (orderId == null || orderId <= 0) {
                return createErrorResponse(101, "OrderId non valido");
            }

            // Chiamata all'OrderManager per cancellazione
            int result = OrderManager.cancelOrder(username, orderId);

            if (result == 100) {
                System.out.println("[ClientHandler] Ordine " + orderId + " cancellato con successo per: " + username);
                return createSuccessResponse("Ordine cancellato con successo");
            } else {
                System.out.println("[ClientHandler] Cancellazione ordine " + orderId + " fallita per: " + username);
                return createErrorResponse(101, "Impossibile cancellare l'ordine");
            }

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante cancelOrder: " + e.getMessage());
            return createErrorResponse(101, "Errore interno durante cancellazione ordine");
        }
    }

    /**
     * Gestisce la richiesta getPriceHistory dal client
     * Calcola dati OHLC (Open, High, Low, Close) per ogni giorno del mese richiesto
     * Formato richiesta ALLEGATO 1: month=STRING(MMYYYY)
     * Formato risposta definito dallo studente: array con dati giornalieri OHLC
     *
     * @param request oggetto JSON con month nel campo "values"
     * @return JsonObject con priceHistory array o messaggio errore
     */
    private JsonObject handleGetPriceHistory(JsonObject request) {
        try {
            // Controllo autenticazione utente
            String username = socketUserMap.get(clientSocket);
            if (username == null) {
                return createPriceHistoryErrorResponse("Utente non loggato");
            }

            if (!request.has("values")) {
                return createPriceHistoryErrorResponse("Messaggio getPriceHistory non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String month = getStringValue(values, "month");

            // Validazione formato mese
            if (month == null || !isValidMonthFormat(month)) {
                return createPriceHistoryErrorResponse("Formato mese non valido. Usare MMYYYY (es: 012025)");
            }

            System.out.println("[ClientHandler] Richiesta dati storici per mese " + month + " da utente: " + username);

            // Calcolo dati OHLC per il mese richiesto
            JsonArray priceHistory = calculateOHLC(month);

            if (priceHistory.size() == 0) {
                return createPriceHistoryErrorResponse("Nessun dato storico disponibile per il mese " + month);
            }

            // Creazione risposta con dati storici
            JsonObject response = new JsonObject();
            response.add("priceHistory", priceHistory);

            System.out.println("[ClientHandler] Inviati " + priceHistory.size() +
                    " giorni di dati storici per mese " + month + " a: " + username);

            return response;

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante getPriceHistory: " + e.getMessage());
            return createPriceHistoryErrorResponse("Errore interno del server durante recupero dati storici");
        }
    }

    /**
     * Calcola dati OHLC per il mese specificato dai trade eseguiti
     * Legge i trade salvati e calcola per ogni giorno: apertura, massimo, minimo, chiusura
     *
     * @param month formato MMYYYY (es: "012025" per gennaio 2025)
     * @return JsonArray con dati giornalieri OHLC
     */
    private JsonArray calculateOHLC(String month) {
        try {
            // Lettura trade eseguiti dal file JSON (implementazione futura)
            // Per ora generiamo dati di esempio per test
            return generateSampleOHLC(month);

        /* TODO: Implementazione completa quando avremo il salvataggio trade
        JsonArray executedTrades = OrderManager.getExecutedTradesForMonth(month);
        return computeOHLCFromTrades(executedTrades, month);
        */

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore calcolo OHLC per mese " + month + ": " + e.getMessage());
            return new JsonArray();
        }
    }

    /**
     * Genera dati OHLC di esempio per test (da sostituire con dati reali)
     *
     * @param month formato MMYYYY
     * @return JsonArray con dati di esempio
     */
    private JsonArray generateSampleOHLC(String month) {
        JsonArray priceHistory = new JsonArray();

        try {
            int mm = Integer.parseInt(month.substring(0, 2));
            int yyyy = Integer.parseInt(month.substring(2, 6));

            // Calcolo giorni nel mese
            int daysInMonth = getDaysInMonth(mm, yyyy);

            // Prezzo base BTC (circa 58.000 USD in millesimi)
            int basePrice = 58000000;

            for (int day = 1; day <= daysInMonth; day++) {
                JsonObject dayData = new JsonObject();

                // Formato data DD/MM/YYYY
                String date = String.format("%02d/%02d/%04d", day, mm, yyyy);

                // Simulazione prezzi OHLC con variazioni casuali
                int open = basePrice + (int)(Math.random() * 2000000 - 1000000);  // ±1000 USD
                int high = open + (int)(Math.random() * 1500000);                 // +0-1500 USD
                int low = open - (int)(Math.random() * 1500000);                  // -0-1500 USD
                int close = low + (int)(Math.random() * (high - low));           // tra low e high

                dayData.addProperty("date", date);
                dayData.addProperty("open", open);
                dayData.addProperty("high", high);
                dayData.addProperty("low", low);
                dayData.addProperty("close", close);

                priceHistory.add(dayData);
            }

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore generazione dati esempio: " + e.getMessage());
        }

        return priceHistory;
    }

    /**
     * Calcola il numero di giorni nel mese specificato
     *
     * @param month mese (1-12)
     * @param year anno
     * @return numero di giorni nel mese
     */
    private int getDaysInMonth(int month, int year) {
        int[] daysInMonth = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

        // Gestione anno bisestile
        if (month == 2 && isLeapYear(year)) {
            return 29;
        }

        return daysInMonth[month];
    }

    /**
     * Verifica se un anno è bisestile
     *
     * @param year anno da verificare
     * @return true se bisestile, false altrimenti
     */
    private boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
    }

    /**
     * Valida il formato del mese MMYYYY
     *
     * @param month stringa da validare
     * @return true se formato corretto, false altrimenti
     */
    private boolean isValidMonthFormat(String month) {
        if (month == null || month.length() != 6) {
            return false;
        }

        try {
            int mm = Integer.parseInt(month.substring(0, 2));
            int yyyy = Integer.parseInt(month.substring(2, 6));

            return mm >= 1 && mm <= 12 && yyyy >= 2020 && yyyy <= 2030;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Crea risposta di errore per getPriceHistory
     *
     * @param errorMessage messaggio di errore
     * @return JsonObject con campo error
     */
    private JsonObject createPriceHistoryErrorResponse(String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("error", errorMessage);
        return response;
    }

    // =============== UTILITY METHODS ===============

    /**
     * Invia una risposta JSON al client
     * Serializza l'oggetto JsonObject in stringa JSON e lo invia
     * tramite il PrintWriter con autoflush abilitato
     *
     * @param response oggetto JsonObject contenente la risposta da serializzare e inviare
     */
    private void sendResponse(JsonObject response) {
        String responseJson = gson.toJson(response);
        out.println(responseJson);
    }

    /**
     * Invia una risposta di errore al client
     * Metodo di utilità per creare e inviare rapidamente risposte di errore
     * Combina creazione dell'oggetto errore e invio in una singola chiamata
     *
     * @param errorCode codice numerico dell'errore
     * @param errorMessage messaggio descrittivo dell'errore per debugging
     */
    private void sendErrorResponse(int errorCode, String errorMessage) {
        JsonObject response = createErrorResponse(errorCode, errorMessage);
        sendResponse(response);
    }

    /**
     * Crea una risposta di successo standard
     * Genera oggetto JSON
     *
     * @param message messaggio descrittivo del successo dell'operazione
     * @return JsonObject formattato
     */
    private JsonObject createSuccessResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", 100);
        response.addProperty("errorMessage", message);
        return response;
    }

    /**
     * Crea una risposta di errore standard
     * Genera oggetto JSON conforme
     *
     * @param errorCode codice numerico dell'errore (101, 102, 103, etc.)
     * @param errorMessage messaggio descrittivo dell'errore
     * @return JsonObject formattato
     */
    private JsonObject createErrorResponse(int errorCode, String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("response", errorCode);
        response.addProperty("errorMessage", errorMessage);
        return response;
    }

    /**
     * Crea una risposta per operazioni ordine secondo formato ALLEGATO 1
     * Formato: { "orderId": NUMBER }
     *
     * @param orderId ID dell'ordine (o -1 per errore)
     * @return JsonObject formattato secondo specifiche
     */
    private JsonObject createOrderResponse(int orderId) {
        JsonObject response = new JsonObject();
        response.addProperty("orderId", orderId);
        return response;
    }

    /**
     * Crea una risposta di errore per operazioni ordine
     * Ritorna orderId = -1 secondo specifiche ALLEGATO 1
     *
     * @param errorMessage messaggio descrittivo dell'errore
     * @return JsonObject con orderId = -1
     */
    private JsonObject createOrderErrorResponse(String errorMessage) {
        System.err.println("[ClientHandler] Errore ordine: " + errorMessage);
        return createOrderResponse(-1);
    }

    /**
     * Estrae un valore stringa da un JsonObject in modo sicuro
     *
     * @param obj oggetto JsonObject da cui estrarre il valore
     * @param key chiave da cercare nell'oggetto JSON
     * @return valore stringa se presente e non null, altrimenti null
     */
    private String getStringValue(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /**
     * Estrae un valore intero da un JsonObject in modo sicuro
     * Necessario per parsing size e price come interi (millesimi)
     *
     * @param obj oggetto JsonObject da cui estrarre il valore
     * @param key chiave da cercare nell'oggetto JSON
     * @return valore intero se presente e valido, altrimenti null
     */
    private Integer getIntegerValue(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException | ClassCastException e) {
                System.err.println("[ClientHandler] Errore parsing intero per chiave " + key + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * Ottiene informazioni sul client per logging e debugging
     * Restituisce username se client è loggato, altrimenti "anonimo"
     * Utilizzato nei log per identificare il client nelle operazioni
     *
     * @return stringa identificativa del client per i messaggi di log
     */
    private String getClientInfo() {
        String username = socketUserMap.get(clientSocket);
        return username != null ? username : "anonimo";
    }

    /**
     * Formatta size in millesimi per logging (es: 1000 -> "1.000 BTC")
     */
    private String formatSize(int sizeInMilliths) {
        return String.format("%.3f", sizeInMilliths / 1000.0);
    }

    /**
     * Formatta price in millesimi per logging (es: 58000000 -> "58000.000 USD")
     */
    private String formatPrice(int priceInMilliths) {
        return String.format("%.3f", priceInMilliths / 1000.0);
    }

    /**
     * Cleanup delle risorse quando il client si disconnette
     * Garantisce rilascio ordinato di tutte le risorse associate al client
     */
    private void cleanup() {
        // Logout automatico se client era loggato
        String username = socketUserMap.remove(clientSocket);
        if (username != null) {
            System.out.println("[ClientHandler] Logout automatico per: " + username +
                    " (disconnessione client)");
        }

        // Chiusura stream di comunicazione
        try {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore chiusura stream: " + e.getMessage());
        }

        // Chiusura socket TCP
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
                System.out.println("[ClientHandler] Socket client chiuso: " +
                        clientSocket.getRemoteSocketAddress());
            }
        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore chiusura socket: " + e.getMessage());
        }
    }
}
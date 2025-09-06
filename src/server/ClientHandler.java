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
        // Switch compatibile Java 8
        switch (operation) {
            case "login":
                return handleLogin(request);
            case "logout":
                return handleLogout(request);
            case "updateCredentials":
                return handleUpdateCredentials(request);
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
                return createErrorResponse(103, "Messaggio updateCredentials non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String oldPassword = getStringValue(values, "old_password");
            String newPassword = getStringValue(values, "new_password");

            // Validazione parametri base
            if (username == null || oldPassword == null || newPassword == null ||
                    username.trim().isEmpty() || oldPassword.trim().isEmpty() || newPassword.trim().isEmpty()) {
                return createErrorResponse(103, "Parametri non validi");
            }

            // Controlla che le password siano diverse
            if (oldPassword.equals(newPassword)) {
                return createErrorResponse(103, "La nuova password deve essere diversa dalla precedente");
            }

            // Verifica che utente NON sia attualmente loggato
            if (socketUserMap.containsValue(username)) {
                return createErrorResponse(104, "Impossibile cambiare password: utente attualmente loggato");
            }

            // Carica utenti e cerca l'utente specificato
            JsonArray users = UserManager.loadUsers();
            JsonObject user = UserManager.findUser(users, username);

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

            System.out.println("[ClientHandler] Password aggiornata per utente: " + username +
                    " (richiesta da connessione non autenticata)");
            return createSuccessResponse("Password aggiornata con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante update credenziali: " + e.getMessage());
            return createErrorResponse(105, "Errore interno durante aggiornamento");
        }
    }

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
     * Verifica se il client è autenticato
     * Controlla presenza del socket nella mappa degli utenti loggati
     *
     * @return true se il client è loggato (socket nella mappa), false altrimenti
     */
    private boolean isAuthenticated() {
        return socketUserMap.containsKey(clientSocket);
    }

    /**
     * Ottiene l'username dell'utente loggato per questo socket
     * Metodo di utilità per recuperare l'utente associato al socket corrente
     * Utilizzato per identificazione nelle operazioni che richiedono autenticazione
     *
     * @return username dell'utente loggato o null se socket non autenticato
     */
    private String getLoggedUsername() {
        return socketUserMap.get(clientSocket);
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
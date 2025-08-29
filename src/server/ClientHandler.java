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

/**
 * Gestisce la comunicazione con un singolo client TCP
 * Ogni istanza viene eseguita da un thread diverso preso dal pool del server
 * Operazioni gestite:
 * - login: autenticazione utente
 * - logout: disconnessione utente
 * - updateCredentials: aggiornamento password
 * Utilizza UserManager per tutte le operazioni sui dati utenti
 */
public class ClientHandler implements Runnable {

    private Socket clientSocket;
    private BufferedReader in;
    private PrintWriter out;
    private Gson gson;

    // Username del client loggato (null se non loggato)
    private String loggedUsername;

    /**
     * Costruttore - inizializza handler per un client specifico
     *
     * @param clientSocket socket della connessione client
     */
    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
        this.gson = new Gson();
        this.loggedUsername = null;
    }

    /**
     * Main del thread - gestisce le comunicazioni con il client
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
            cleanup();
        }
    }

    /**
     * Inizializza gli stream di input/output per la comunicazione JSON
     */
    private void setupStreams() throws IOException {
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new PrintWriter(clientSocket.getOutputStream(), true);
    }

    /**
     * Loop principale che gestisce i messaggi JSON dal client
     */
    private void handleClientMessages() throws IOException {
        String messageJson;

        while ((messageJson = in.readLine()) != null) {
            try {
                // Parsing del messaggio JSON
                JsonObject request = JsonParser.parseString(messageJson).getAsJsonObject();

                if (!request.has("operation")) {
                    sendErrorResponse(103, "Messaggio JSON non valido: manca campo operation");
                    continue;
                }

                String operation = request.get("operation").getAsString();
                System.out.println("[ClientHandler] Operazione ricevuta: " + operation +
                        " da " + getClientInfo());

                // Dispatch dell'operazione
                JsonObject response = processOperation(operation, request);

                // Invio della risposta
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
     */
    private JsonObject processOperation(String operation, JsonObject request) {
        return switch (operation) {
            case "login" -> handleLogin(request);
            case "logout" -> handleLogout(request);
            case "updateCredentials" -> handleUpdateCredentials(request);
            default -> createErrorResponse(103, "Operazione non supportata: " + operation);
        };
    }

    /**
     * Gestisce il login del client
     */
    private JsonObject handleLogin(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createErrorResponse(103, "Messaggio login non valido: manca campo values");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String password = getStringValue(values, "password");

            // Validazione parametri
            if (!UserManager.validateLoginParams(username, password)) {
                return createErrorResponse(103, "Parametri login non validi");
            }

            // Carica utenti e cerca l'utente
            JsonArray users = UserManager.loadUsers();
            JsonObject user = UserManager.findUser(users, username);

            if (user == null) {
                System.out.println("[ClientHandler] Login fallito: utente non esistente - " + username);
                return createErrorResponse(101, "Username/password non corrispondenti");
            }

            // Controlla password
            String storedPassword = user.get("password").getAsString();
            if (!storedPassword.equals(password)) {
                System.out.println("[ClientHandler] Login fallito: password errata - " + username);
                return createErrorResponse(101, "Username/password non corrispondenti");
            }

            // Controlla se già loggato
            if (UserManager.isUserLoggedIn(user)) {
                System.out.println("[ClientHandler] Login fallito: utente già loggato - " + username);
                return createErrorResponse(102, "Utente già loggato");
            }

            // Effettua login
            UserManager.updateLoginStatus(user, true);
            UserManager.saveUsers(users);

            // Imposta utente loggato per questa connessione
            loggedUsername = username;

            System.out.println("[ClientHandler] Login successful per: " + username);
            return createSuccessResponse("Login effettuato con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante login: " + e.getMessage());
            return createErrorResponse(103, "Errore interno durante login");
        }
    }

    /**
     * Gestisce il logout del client
     */
    private JsonObject handleLogout(JsonObject request) {
        try {
            // Controlla se l'utente è loggato
            if (loggedUsername == null) {
                return createErrorResponse(101, "Utente non loggato");
            }

            // Carica utenti e trova l'utente loggato
            JsonArray users = UserManager.loadUsers();
            JsonObject user = UserManager.findUser(users, loggedUsername);

            if (user == null) {
                return createErrorResponse(101, "Utente non trovato");
            }

            // Effettua logout
            UserManager.updateLoginStatus(user, false);
            UserManager.saveUsers(users);

            System.out.println("[ClientHandler] Logout per: " + loggedUsername);

            // Reset username loggato
            String loggedOutUser = loggedUsername;
            loggedUsername = null;

            return createSuccessResponse("Logout effettuato per utente: " + loggedOutUser);

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante logout: " + e.getMessage());
            return createErrorResponse(101, "Errore interno durante logout");
        }
    }

    /**
     * Gestisce l'aggiornamento delle credenziali
     */
    private JsonObject handleUpdateCredentials(JsonObject request) {
        try {
            if (!request.has("values")) {
                return createErrorResponse(105, "Messaggio updateCredentials non valido");
            }

            JsonObject values = request.getAsJsonObject("values");
            String username = getStringValue(values, "username");
            String oldPassword = getStringValue(values, "old_password");
            String newPassword = getStringValue(values, "new_password");

            // Validazione parametri base
            if (username == null || username.trim().isEmpty() ||
                    oldPassword == null || newPassword == null) {
                return createErrorResponse(105, "Parametri non validi");
            }

            // Controlla che la nuova password non sia vuota
            if (newPassword.trim().isEmpty()) {
                return createErrorResponse(101, "Nuova password non valida");
            }

            // Controlla che le password siano diverse
            if (oldPassword.equals(newPassword)) {
                return createErrorResponse(103, "La nuova password deve essere diversa dalla precedente");
            }

            // Carica utenti e cerca l'utente
            JsonArray users = UserManager.loadUsers();
            JsonObject user = UserManager.findUser(users, username);

            if (user == null) {
                return createErrorResponse(102, "Username non esistente");
            }

            // Controlla password attuale
            String currentPassword = user.get("password").getAsString();
            if (!currentPassword.equals(oldPassword)) {
                return createErrorResponse(102, "Password attuale non corretta");
            }

            // Controlla se l'utente è attualmente loggato
            if (UserManager.isUserLoggedIn(user)) {
                return createErrorResponse(104, "Impossibile cambiare password: utente attualmente loggato");
            }

            // Aggiorna la password
            user.addProperty("password", newPassword);
            user.addProperty("lastPasswordUpdate", System.currentTimeMillis());

            // Salva utenti aggiornati
            UserManager.saveUsers(users);

            System.out.println("[ClientHandler] Password aggiornata per utente: " + username);
            return createSuccessResponse("Password aggiornata con successo");

        } catch (Exception e) {
            System.err.println("[ClientHandler] Errore durante update credenziali: " + e.getMessage());
            return createErrorResponse(105, "Errore interno durante aggiornamento");
        }
    }



    /**
     * Invia una risposta JSON al client
     */
    private void sendResponse(JsonObject response) {
        String responseJson = gson.toJson(response);
        out.println(responseJson);
    }

    /**
     * Invia una risposta di errore al client
     */
    private void sendErrorResponse(int errorCode, String errorMessage) {
        JsonObject response = createErrorResponse(errorCode, errorMessage);
        sendResponse(response);
    }

    /**
     * Crea una risposta di successo standard
     */
    private JsonObject createSuccessResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", 100);
        response.addProperty("errorMessage", message);
        return response;
    }

    /**
     * Crea una risposta di errore standard
     */
    private JsonObject createErrorResponse(int errorCode, String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("response", errorCode);
        response.addProperty("errorMessage", errorMessage);
        return response;
    }

    /**
     * Estrae un valore stringa da un JsonObject in modo sicuro
     */
    private String getStringValue(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /**
     * Ottiene informazioni sul client per logging
     */
    private String getClientInfo() {
        return loggedUsername != null ? loggedUsername : "anonimo";
    }

    /**
     * Verifica se il client è autenticato
     */
    private boolean isAuthenticated() {
        return loggedUsername != null;
    }

    /**
     * Cleanup risorse quando il client si disconnette
     */
    private void cleanup() {
        try {
            // Se l'utente era loggato, effettua logout automatico
            if (loggedUsername != null) {
                try {
                    JsonArray users = UserManager.loadUsers();
                    JsonObject user = UserManager.findUser(users, loggedUsername);
                    if (user != null) {
                        UserManager.updateLoginStatus(user, false);
                        UserManager.saveUsers(users);
                        System.out.println("[ClientHandler] Logout automatico per disconnessione: " + loggedUsername);
                    }
                } catch (Exception e) {
                    System.err.println("[ClientHandler] Errore logout automatico: " + e.getMessage());
                }
                loggedUsername = null;
            }

            System.out.println("[ClientHandler] Disconnessione client: " +
                    clientSocket.getRemoteSocketAddress());

            // Chiude gli stream
            if (in != null) in.close();
            if (out != null) out.close();
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }

        } catch (IOException e) {
            System.err.println("[ClientHandler] Errore cleanup: " + e.getMessage());
        }
    }
}
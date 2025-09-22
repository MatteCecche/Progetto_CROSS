package server.utility;

import com.google.gson.JsonObject;

/**
 * Utility class per la costruzione standardizzata delle risposte JSON
 * Implementa i formati esatti richiesti con codici specifici per ogni operazione
 */
public class ResponseBuilder {

    // === OPERAZIONI AUTENTICAZIONE ===

    /**
     * REGISTER - Codici secondo ALLEGATO 1
     * 100 - OK
     * 101 - invalid password
     * 102 - username not available
     * 103 - other error cases
     */
    public static class Register {
        public static JsonObject success() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("errorMessage", "Registrazione completata con successo");
            return response;
        }

        public static JsonObject invalidPassword() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 101);
            response.addProperty("errorMessage", "Password non valida");
            return response;
        }

        public static JsonObject usernameNotAvailable() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 102);
            response.addProperty("errorMessage", "Username non disponibile");
            return response;
        }

        public static JsonObject otherError(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("response", 103);
            response.addProperty("errorMessage", message);
            return response;
        }
    }

    /**
     * UPDATE CREDENTIALS - Codici secondo ALLEGATO 1
     * 100 - OK
     * 101 - invalid password
     * 102 - username not found
     * 103 - other error cases
     * 104 - user already logged (requirement specifico)
     * 105 - internal error
     */
    public static class UpdateCredentials {
        public static JsonObject success() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("errorMessage", "Password aggiornata con successo");
            return response;
        }

        public static JsonObject invalidPassword() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 101);
            response.addProperty("errorMessage", "Password non valida");
            return response;
        }

        public static JsonObject usernameNotFound() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 102);
            response.addProperty("errorMessage", "Username non trovato o password attuale errata");
            return response;
        }

        public static JsonObject userAlreadyLogged() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 104);
            response.addProperty("errorMessage", "Impossibile aggiornare password: utente attualmente loggato");
            return response;
        }

        public static JsonObject otherError(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("response", 103);
            response.addProperty("errorMessage", message);
            return response;
        }
    }

    /**
     * LOGIN - Codici secondo ALLEGATO 1
     * 100 - OK
     * 101 - wrong credentials
     * 102 - user already logged
     * 103 - other error cases
     */
    public static class Login {
        public static JsonObject success() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("errorMessage", "Login effettuato con successo");
            return response;
        }

        public static JsonObject wrongCredentials() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 101);
            response.addProperty("errorMessage", "Credenziali non valide");
            return response;
        }

        public static JsonObject userAlreadyLogged() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 102);
            response.addProperty("errorMessage", "Utente già loggato");
            return response;
        }

        public static JsonObject otherError(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("response", 103);
            response.addProperty("errorMessage", message);
            return response;
        }
    }

    /**
     * LOGOUT - Codici secondo ALLEGATO 1
     * 100 - OK
     * 101 - user not logged
     * 103 - other error cases
     */
    public static class Logout {
        public static JsonObject success() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("errorMessage", "Logout effettuato con successo");
            return response;
        }

        public static JsonObject userNotLogged() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 101);
            response.addProperty("errorMessage", "Utente non loggato");
            return response;
        }

        public static JsonObject otherError(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("response", 103);
            response.addProperty("errorMessage", message);
            return response;
        }
    }

    // === OPERAZIONI TRADING ===

    /**
     * INSERT LIMIT/MARKET/STOP ORDER - Formato secondo ALLEGATO 1
     * Successo: {"orderId": NUMBER}
     * Errore: {"orderId": -1} (o messaggio di errore)
     */
    public static class TradingOrder {
        public static JsonObject success(int orderId) {
            JsonObject response = new JsonObject();
            response.addProperty("orderId", orderId);
            return response;
        }

        public static JsonObject error() {
            JsonObject response = new JsonObject();
            response.addProperty("orderId", -1);
            return response;
        }

        public static JsonObject errorWithMessage(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("error", message);
            return response;
        }
    }

    /**
     * CANCEL ORDER - Codici secondo ALLEGATO 1
     * 100 - OK
     * 101 - order error (non esistente, già eseguito, non di proprietà)
     */
    public static class CancelOrder {
        public static JsonObject success() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 100);
            response.addProperty("errorMessage", "Ordine cancellato con successo");
            return response;
        }

        public static JsonObject orderError(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("response", 101);
            response.addProperty("errorMessage", message);
            return response;
        }
    }

    // === UTILITY GENERICHE ===

    /**
     * Crea risposta generica con codice e messaggio custom
     * Usare solo quando nessuna delle classi specifiche si adatta
     */
    public static JsonObject createGenericResponse(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }

    /**
     * Verifica se un codice rappresenta successo (100)
     */
    public static boolean isSuccessCode(int code) {
        return code == 100;
    }
}

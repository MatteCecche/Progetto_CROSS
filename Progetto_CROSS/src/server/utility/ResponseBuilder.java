package server.utility;

import com.google.gson.JsonObject;

/*
 * Costruisce le risposte JSON standardizzate per le operazioni
 */
public class ResponseBuilder {

    // REGISTER: 100=OK, 101=invalid password, 102=username not available, 103=other
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

    // UPDATE CREDENTIALS: 100=OK, 101=invalid password, 102=wrong credentials, 103=same password, 104=logged, 105=other
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

        public static JsonObject passwordEqualToOld() {
            JsonObject response = new JsonObject();
            response.addProperty("response", 103);
            response.addProperty("errorMessage", "La nuova password deve essere diversa dalla vecchia");
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
            response.addProperty("response", 105);
            response.addProperty("errorMessage", message);
            return response;
        }
    }

    // LOGIN: 100=OK, 101=wrong credentials, 102=already logged, 103=other
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

    // LOGOUT: 100=OK, 101=not logged, 103=other
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

    // TRADING ORDER: success ritorna orderId, error ritorna -1
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

    // CANCEL ORDER: 100=OK, 101=error
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

    public static JsonObject createGenericResponse(int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("response", code);
        response.addProperty("errorMessage", message);
        return response;
    }
}
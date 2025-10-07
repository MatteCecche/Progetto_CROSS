package server.utility;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Classe di utilità per la gestione degli utenti del sistema
 * - Caricamento e salvataggio utenti da/in file JSON
 * - Ricerca e validazione utenti
 * - Creazione nuovi utenti
 * - Persistenza periodica per sincronizzazione dati
 */

public class UserManager {

    // File per memorizzare gli utenti registrati in formato JSON
    private static final String USERS_FILE = "data/users.json";

    // Lock per sincronizzazione accesso concorrente al file utenti
    private static final ReentrantReadWriteLock usersLock = new ReentrantReadWriteLock();

    // Configurazione Gson per JSON formattato
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    //Inizializza il sistema di gestione utenti
    public static void initialize() throws IOException {
        // Verifica esistenza directory data
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            throw new IOException(
                    "[UserManager] Errore: Directory 'data/' non trovata!\n");
        }

        if (!dataDir.isDirectory()) {
            throw new IOException(
                    "[UserManager] Errore: 'data/' esiste ma non è una directory!\n");
        }

        // Verifica esistenza file users.json
        File usersFile = new File(USERS_FILE);
        if (!usersFile.exists()) {
            throw new IOException(
                    "[UserManager] Errore: File 'data/users.json' non trovato!\n");
        }

        if (!usersFile.isFile()) {
            throw new IOException(
                    "[UserManager] Errore: 'data/users.json' esiste ma non è un file!\n");
        }

        // Verifica che il file non sia vuoto e abbia una struttura valida
        if (usersFile.length() == 0) {
            throw new IOException(
                    "[UserManager] Errore: File 'data/users.json' è vuoto!\n");
        }

        // Tenta di caricare il file per verificare che sia un JSON valido
        try {
            JsonArray users = loadUsersInternal();
        } catch (Exception e) {
            throw new IOException(
                    "[UserManager] Errore: File 'data/users.json' non è un JSON valido!\n");
        }
    }


    //Carica la lista degli utenti dal file JSON
    public static JsonArray loadUsers() throws IOException {
        usersLock.readLock().lock();
        try {
            return loadUsersInternal();
        } finally {
            usersLock.readLock().unlock();
        }
    }

    //Salva la lista degli utenti nel file JSON
    public static void saveUsers(JsonArray users) throws IOException {
        usersLock.writeLock().lock();
        try {
            JsonObject rootObject = new JsonObject();
            rootObject.add("users", users);

            try (FileWriter writer = new FileWriter(USERS_FILE)) {
                gson.toJson(rootObject, writer);
            }
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    //Cerca un utente per username nella lista
    public static JsonObject findUser(JsonArray users, String username) {
        if (username == null) return null;

        for (int i = 0; i < users.size(); i++) {
            JsonObject user = users.get(i).getAsJsonObject();
            String existingUsername = user.get("username").getAsString();

            if (existingUsername.equals(username.trim())) {
                return user;
            }
        }
        return null;
    }

    //Controlla se un username esiste già nel sistema
    public static boolean isUsernameExists(String username) throws IOException {
        JsonArray users = loadUsers();
        return findUser(users, username) != null;
    }

    //Crea un nuovo oggetto utente con i parametri specificati
    public static JsonObject createUser(String username, String password) {
        JsonObject newUser = new JsonObject();
        newUser.addProperty("username", username.trim());
        newUser.addProperty("password", password);
        return newUser;
    }

    //Valida i parametri di registrazione
    public static int validateRegistrationParams(String username, String password) throws IOException {
        // Controlla username vuoto o null
        if (username == null || username.trim().isEmpty()) {
            return 103;
        }

        // Controlla password vuota o null
        if (password == null || password.trim().isEmpty()) {
            return 101;
        }

        // Controlla se username già esistente
        if (isUsernameExists(username)) {
            return 102;
        }

        return 100;
    }

    //Valida i parametri di login
    public static boolean validateLoginParams(String username, String password) {
        return username != null && !username.trim().isEmpty() &&
                password != null && !password.trim().isEmpty();
    }

    //Getter per il lock degli utenti
    public static ReentrantReadWriteLock getUsersLock() {
        return usersLock;
    }

    //Valida le credenziali di un utente confrontando username e password
    public static boolean validateCredentials(String username, String password) {
        try {
            if (username == null || password == null ||
                    username.trim().isEmpty() || password.trim().isEmpty()) {
                return false;
            }

            // Carica lista utenti dal file JSON
            JsonArray users = loadUsers();

            // Cerca l'utente nella lista
            JsonObject user = findUser(users, username);
            if (user == null) {
                return false;
            }

            // Confronta password
            String storedPassword = user.get("password").getAsString();
            return password.equals(storedPassword);

        } catch (Exception e) {
            System.err.println("[UserManager] Errore validazione credenziali: " + e.getMessage());
            return false;
        }
    }

    //Valida una password secondo i criteri del sistema
    public static boolean isValidPassword(String password) {

        if (password == null) {
            return false;
        }

        // La password non può essere completamente vuota
        if (password.trim().isEmpty()) {
            return false;
        }

        return true;
    }

    //Aggiorna la password di un utente esistente nel sistema
    public static int updatePassword(String username, String oldPassword, String newPassword) {
        usersLock.writeLock().lock();
        try {
            // Validazione nuova password
            if (!isValidPassword(newPassword)) {
                return 101;
            }

            // Verifica che nuova password sia diversa dalla vecchia
            if (oldPassword.equals(newPassword)) {
                return 103;
            }

            // Carica lista utenti
            JsonArray users = loadUsers();

            // Cerca l'utente
            JsonObject user = findUser(users, username);
            if (user == null) {
                return 102;
            }

            // Verifica password attuale
            String storedPassword = user.get("password").getAsString();
            if (!oldPassword.equals(storedPassword)) {
                return 102;
            }

            // Aggiorna password nell'oggetto utente
            user.addProperty("password", newPassword);

            // Salva lista utenti aggiornata
            saveUsers(users);

            System.out.println("[UserManager] Password aggiornata per utente: " + username);
            return 100; // OK

        } catch (Exception e) {
            System.err.println("[UserManager] Errore aggiornamento password: " + e.getMessage());
            return 105;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    //Getter per il path del file utenti
    public static String getUsersFilePath() {
        return USERS_FILE;
    }

    //Inizializza il file degli utenti con una struttura JSON vuota
    private static void initializeUsersFile() throws IOException {
        JsonObject rootObject = new JsonObject();
        rootObject.add("users", new JsonArray());

        try (FileWriter writer = new FileWriter(USERS_FILE)) {
            gson.toJson(rootObject, writer);
        }
    }

    //Salva tutti gli utenti correnti dal sistema
    public static void saveAllUsers() throws IOException {
        usersLock.writeLock().lock();
        try {
            // Carica gli utenti correnti
            JsonArray users = loadUsersInternal();

            // Salva la lista
            JsonObject rootObject = new JsonObject();
            rootObject.add("users", users);

            try (FileWriter writer = new FileWriter(USERS_FILE)) {
                gson.toJson(rootObject, writer);
                writer.flush(); // Forza scrittura fisica su disco
            }

            System.out.println("[UserManager] Salvati " + users.size() + " utenti nel file JSON");

        } finally {
            usersLock.writeLock().unlock();
        }
    }

    //Metodo interno per caricare utenti senza acquisire il lock
    private static JsonArray loadUsersInternal() throws IOException {
        File usersFile = new File(USERS_FILE);

        // Controllo esistenza e dimensione file
        if (!usersFile.exists() || usersFile.length() == 0) {
            return new JsonArray(); // File non esiste o è vuoto, lista vuota
        }

        try (FileReader reader = new FileReader(usersFile)) {
            JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();

            // Controlla se il file contiene la struttura corretta
            if (rootObject != null && rootObject.has("users")) {
                return rootObject.getAsJsonArray("users");
            } else {
                System.out.println("[UserManager] File users.json corrotto, reinizializzazione...");
                initializeUsersFile();
                return new JsonArray();
            }
        } catch (Exception e) {
            System.err.println("[UserManager] Errore lettura file utenti, reinizializzazione: " + e.getMessage());
            initializeUsersFile();
            return new JsonArray();
        }
    }
}
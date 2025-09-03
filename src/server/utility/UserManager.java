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
 * Fornisce metodi centralizzati per:
 * - Caricamento e salvataggio utenti da/in file JSON
 * - Ricerca e validazione utenti
 * - Sincronizzazione thread-safe
 */
public class UserManager {

    // File per memorizzare gli utenti registrati in formato JSON
    private static final String USERS_FILE = "data/users.json";

    // Lock per sincronizzazione accesso concorrente al file utenti
    private static final ReentrantReadWriteLock usersLock = new ReentrantReadWriteLock();

    // Gson per parsing e generazione JSON
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * Inizializza il sistema di gestione utenti
     * Crea la directory data e il file utenti se non esistono
     *
     * @throws IOException se errori nell'inizializzazione
     */
    public static void initialize() throws IOException {
        // Crea directory data se non esiste
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            System.out.println("[UserManager] Creata directory data/");
        }

        // Crea file utenti vuoto se non esiste
        File usersFile = new File(USERS_FILE);
        if (!usersFile.exists()) {
            initializeUsersFile();
            System.out.println("[UserManager] Inizializzato file users.json");
        }

        System.out.println("[UserManager] Sistema gestione utenti inizializzato");
    }

    /**
     * Carica la lista degli utenti dal file JSON
     * Metodo thread-safe con lock in lettura
     *
     * @return JsonArray contenente tutti gli utenti registrati
     * @throws IOException se errori nella lettura del file
     */
    public static JsonArray loadUsers() throws IOException {
        usersLock.readLock().lock();
        try {
            File usersFile = new File(USERS_FILE);

            if (!usersFile.exists() || usersFile.length() == 0) {
                return new JsonArray(); // File non esiste o è vuoto, lista vuota
            }

            try (FileReader reader = new FileReader(usersFile)) {
                JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();

                // Controlla se il file contiene la struttura corretta
                if (rootObject != null && rootObject.has("users")) {
                    return rootObject.getAsJsonArray("users");
                } else {
                    // File corrotto o struttura non valida, reinizializza
                    System.out.println("[UserManager] File users.json corrotto, reinizializzazione...");
                    initializeUsersFile();
                    return new JsonArray();
                }
            } catch (Exception e) {
                // Se c'è un errore nel parsing, reinizializza il file
                System.err.println("[UserManager] Errore lettura file utenti, reinizializzazione: " + e.getMessage());
                initializeUsersFile();
                return new JsonArray();
            }
        } finally {
            usersLock.readLock().unlock();
        }
    }

    /**
     * Salva la lista degli utenti nel file JSON
     * Metodo thread-safe con lock in scrittura
     *
     * @param users JsonArray contenente tutti gli utenti da salvare
     * @throws IOException se errori nella scrittura del file
     */
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

    /**
     * Cerca un utente per username nella lista
     *
     * @param users lista degli utenti
     * @param username username da cercare
     * @return JsonObject dell'utente se trovato, null altrimenti
     */
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

    /**
     * Controlla se un username esiste già nel sistema
     *
     * @param username username da verificare
     * @return true se l'username esiste già, false altrimenti
     * @throws IOException se errori nell'accesso al file
     */
    public static boolean isUsernameExists(String username) throws IOException {
        JsonArray users = loadUsers();
        return findUser(users, username) != null;
    }

    /**
     * Crea un nuovo oggetto utente con i parametri specificati
     *
     * @param username nome utente
     * @param password password utente
     * @return JsonObject rappresentante il nuovo utente
     */
    public static JsonObject createUser(String username, String password) {
        JsonObject newUser = new JsonObject();
        newUser.addProperty("username", username.trim());
        newUser.addProperty("password", password);
        return newUser;
    }

    /**
     * Valida i parametri di registrazione
     *
     * @param username nome utente da validare
     * @param password password da validare
     * @return codice di errore o 0 se valido:
     *         0 - parametri validi
     *         101 - password vuota/non valida
     *         103 - username vuoto/non valido
     */
    public static int validateRegistrationParams(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return 103; // username non valido
        }

        if (password == null || password.trim().isEmpty()) {
            return 101; // password non valida
        }

        return 0; // parametri validi
    }

    /**
     * Valida i parametri di login
     *
     * @param username nome utente da validare
     * @param password password da validare
     * @return true se i parametri sono validi, false altrimenti
     */
    public static boolean validateLoginParams(String username, String password) {
        return username != null && !username.trim().isEmpty() &&
                password != null && !password.isEmpty();
    }

    /**
     * Getter per il lock degli utenti
     * Permette sincronizzazione avanzata se necessaria
     *
     * @return ReentrantReadWriteLock per sincronizzazione
     */
    public static ReentrantReadWriteLock getUsersLock() {
        return usersLock;
    }

    /**
     * Getter per il path del file utenti
     *
     * @return String con il percorso del file utenti
     */
    public static String getUsersFilePath() {
        return USERS_FILE;
    }

    /**
     * Inizializza il file degli utenti con una struttura JSON vuota
     *
     * @throws IOException se errori nella creazione del file
     */
    private static void initializeUsersFile() throws IOException {
        JsonObject rootObject = new JsonObject();
        rootObject.add("users", new JsonArray());

        try (FileWriter writer = new FileWriter(USERS_FILE)) {
            gson.toJson(rootObject, writer);
        }
    }
}
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
 * Classe di utilità per la gestione degli utenti del sistema CROSS
 * - Caricamento e salvataggio utenti da/in file JSON con formattazione
 * - Ricerca e validazione utenti
 * - Creazione nuovi utenti
 * - Sincronizzazione thread-safe per accessi concorrenti
 *
 * Implementa il pattern Utility Class con metodi statici per centralizzare
 * tutta la logica di gestione della persistenza utenti in formato JSON.
 * Utilizza ReentrantReadWriteLock per ottimizzare le operazioni di lettura concorrenti.
 */
public class UserManager {

    // File per memorizzare gli utenti registrati in formato JSON
    private static final String USERS_FILE = "data/users.json";

    // Lock per sincronizzazione accesso concorrente al file utenti
    // ReadWrite lock permette letture multiple simultanee ma scritture esclusive
    private static final ReentrantReadWriteLock usersLock = new ReentrantReadWriteLock();

    // Configurazione Gson
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    /**
     * Inizializza il sistema di gestione utenti
     * Configura il filesystem e prepara le strutture dati necessarie per il funzionamento
     * Crea la directory data e il file utenti se non esistono
     *
     * @throws IOException se errori nell'inizializzazione del filesystem o creazione file
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
     * Implementa lettura thread-safe con lock in lettura per permettere accessi concorrenti
     * Il lock in lettura permette a più thread di leggere contemporaneamente
     * ma blocca durante le operazioni di scrittura
     *
     * @return JsonArray contenente tutti gli utenti registrati nel sistema
     * @throws IOException se errori critici nella lettura del file che non possono essere risolti
     */
    public static JsonArray loadUsers() throws IOException {
        usersLock.readLock().lock();
        try {
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
     * Implementa scrittura thread-safe con lock esclusivo per evitare corruzioni
     *
     * @param users JsonArray contenente tutti gli utenti da salvare su file
     * @throws IOException se errori nella scrittura del file o problemi di I/O
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
     * @param users lista degli utenti in cui effettuare la ricerca
     * @param username username da cercare (viene applicato trim automaticamente)
     * @return JsonObject dell'utente se trovato, null se non trovato o parametri non validi
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
     * @param username username da verificare nel sistema
     * @return true se l'username esiste già, false altrimenti
     * @throws IOException se errori nell'accesso al file degli utenti
     */
    public static boolean isUsernameExists(String username) throws IOException {
        JsonArray users = loadUsers();
        return findUser(users, username) != null;
    }

    /**
     * Crea un nuovo oggetto utente con i parametri specificati
     *
     * @param username nome utente scelto (viene applicato trim automaticamente)
     * @param password password scelta dall'utente (mantenuta come fornita)
     * @return JsonObject rappresentante il nuovo utente pronto per essere aggiunto alla lista
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
     * @return codice di errore o 0 se i parametri sono validi
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
     * Valida i parametri di login con controlli più permissivi rispetto alla registrazione
     * permette password che contengono solo spazi (tecnicamente valide per login)
     * ma impedisce password completamente vuote
     *
     * @param username nome utente da validare per login
     * @param password password da validare per login
     * @return true se i parametri sono sufficienti per tentare il login, false altrimenti
     */
    public static boolean validateLoginParams(String username, String password) {
        return username != null && !username.trim().isEmpty() &&
                password != null && !password.isEmpty();
    }

    /**
     * Getter per il lock degli utenti
     *
     * @return ReentrantReadWriteLock per sincronizzazione esterna
     */
    public static ReentrantReadWriteLock getUsersLock() {
        return usersLock;
    }

    /**
     * Getter per il path del file utenti
     *
     * @return String contenente il percorso completo del file utenti
     */
    public static String getUsersFilePath() {
        return USERS_FILE;
    }

    /**
     * Inizializza il file degli utenti con una struttura JSON vuota
     * Crea la struttura base del file
     *
     * @throws IOException se errori nella creazione o scrittura del file
     */
    private static void initializeUsersFile() throws IOException {
        JsonObject rootObject = new JsonObject();
        rootObject.add("users", new JsonArray());

        try (FileWriter writer = new FileWriter(USERS_FILE)) {
            gson.toJson(rootObject, writer);
        }
    }
}
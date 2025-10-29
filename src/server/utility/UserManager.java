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
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Gestisce gli utenti del sistema salvandoli in un file JSON
 */
public class UserManager {

    // File per memorizzare gli utenti registrati
    private static final String USERS_FILE = "data/users.json";

    // Oggetto lock per sincronizzazione
    private static final Object FILE_LOCK = new Object();

    // Configurazione Gson
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    // Controlla che tutto sia a posto prima di partire
    public static void initialize() throws IOException {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            throw new IOException(ANSI_RED + "[UserManager] Cartella 'data' non trovata\n" + ANSI_RESET);
        }

        File usersFile = new File(USERS_FILE);
        if (!usersFile.exists()) {
            throw new IOException(ANSI_RED + "[UserManager] File 'users.json' non trovato\n" + ANSI_RESET);
        }

        // Se il file è vuoto lo inizializzo
        if (usersFile.length() == 0) {
            initializeUsersFile();
        }
    }

    private static void initializeUsersFile() throws IOException {
        JsonObject rootObject = new JsonObject();
        rootObject.add("users", new JsonArray());

        try (FileWriter writer = new FileWriter(USERS_FILE)) {
            gson.toJson(rootObject, writer);
        }
    }

    public static JsonObject createUser(String username, String password) {
        JsonObject newUser = new JsonObject();
        newUser.addProperty("username", username.trim());
        newUser.addProperty("password", password);
        return newUser;
    }

    // Controlla se i dati di registrazione sono validi
    public static int validateRegistrationParams(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return 103;
        }
        if (password == null || password.trim().isEmpty()) {
            return 101;
        }
        return 100;
    }

    public static boolean validateLoginParams(String username, String password) {
        // Controllo username
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        // Controllo password
        if (password == null || password.trim().isEmpty()) {
            return false;
        }

        return true;
    }

    public static boolean validateCredentials(String username, String password) {
        try {
            if (username == null || password == null || username.trim().isEmpty() || password.trim().isEmpty()) {
                return false;
            }

            JsonArray users = loadUsers();
            JsonObject user = findUser(users, username);

            if (user == null) {
                return false;
            }

            String storedPassword = user.get("password").getAsString();
            return password.equals(storedPassword);

        } catch (Exception e) {
            System.err.println(ANSI_RED + "[UserManager] Errore validazione: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    public static int updatePassword(String username, String oldPassword, String newPassword, ConcurrentHashMap<Socket, String> socketUserMap) {
        synchronized (FILE_LOCK) {
            try {
                if (!isValidPassword(newPassword)) {
                    return 101;
                }

                if (oldPassword.equals(newPassword)) {
                    return 103;
                }

                if (isUserLoggedIn(username, socketUserMap)) {
                    return 104;
                }

                JsonArray users = loadUsers();
                JsonObject user = findUser(users, username);

                if (user == null) {
                    return 102;
                }

                String storedPassword = user.get("password").getAsString();
                if (!oldPassword.equals(storedPassword)) {
                    return 102;
                }

                user.addProperty("password", newPassword);
                saveUsers(users);

                System.out.println(ANSI_GREEN + "[UserManager] Password aggiornata per: " + username + ANSI_RESET);
                return 100;

            } catch (Exception e) {
                System.err.println(ANSI_RED + "[UserManager] Errore aggiornamento password: " + e.getMessage() + ANSI_RESET);
                return 105;
            }
        }
    }

    public static boolean isValidPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            return false;
        }
        return true;
    }

    public static boolean isUserLoggedIn(String username, ConcurrentHashMap<Socket, String> socketUserMap) {
        if (username == null || socketUserMap == null) {
            return false;
        }
        return socketUserMap.containsValue(username);
    }

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

    public static void saveUsers(JsonArray users) throws IOException {
        synchronized (FILE_LOCK) {
            JsonObject rootObject = new JsonObject();
            rootObject.add("users", users);
            try (FileWriter writer = new FileWriter(USERS_FILE)) {
                gson.toJson(rootObject, writer);
                writer.flush(); // Aggiunto per sicurezza
            }
        }
    }

    public static void saveAllUsers() throws IOException {
        synchronized (FILE_LOCK) {
            JsonArray users = loadUsers();
            JsonObject rootObject = new JsonObject();
            rootObject.add("users", users);
            try (FileWriter writer = new FileWriter(USERS_FILE)) {
                gson.toJson(rootObject, writer);
                writer.flush();
            }
        }
    }

    public static JsonArray loadUsers() throws IOException {
        synchronized (FILE_LOCK) {
            File usersFile = new File(USERS_FILE);

            if (!usersFile.exists() || usersFile.length() == 0) {
                return new JsonArray();
            }

            try (FileReader reader = new FileReader(usersFile)) {
                JsonObject rootObject = JsonParser.parseReader(reader).getAsJsonObject();

                if (rootObject != null && rootObject.has("users")) {
                    return rootObject.getAsJsonArray("users");
                } else {
                    initializeUsersFile();
                    return new JsonArray();
                }
            } catch (Exception e) {
                System.err.println(ANSI_RED + "[UserManager] Errore lettura file: " + e.getMessage() + ANSI_RESET);
                initializeUsersFile();
                return new JsonArray();
            }
        }
    }
    public static Object getFileLock() {

        return FILE_LOCK;
    }
}
package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import common.RegistrazioneRMI;
import server.utility.UserManager;

/*
 * Servizio RMI per la registrazione degli utenti
 */
public class RegistrazioneRMIImpl extends UnicastRemoteObject implements RegistrazioneRMI {

    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_RESET = "\u001B[0m";

    public RegistrazioneRMIImpl() throws RemoteException {
        super();
        try {
            UserManager.initialize();
        } catch (Exception e) {
            throw new RemoteException(ANSI_RED + "[RegistrazioneRMI] Errore inizializzazione servizio registrazione" + ANSI_RESET, e);
        }
    }

    public int register(String username, String password) throws RemoteException {
        try {
            System.out.println(ANSI_GREEN + "[RegistrazioneRMI] Richiesta registrazione: " + username + ANSI_RESET);

            // Validazione con metodo dedicato
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 100) {
                return validationResult;
            }

            synchronized (UserManager.getFileLock()) {
                JsonArray users = UserManager.loadUsers();

                if (UserManager.findUser(users, username) != null) {
                    System.out.println(ANSI_RED + "[RegistrazioneRMI] Utente già esistente: " + username + ANSI_RESET);
                    return 102;
                }

                JsonObject newUser = UserManager.createUser(username, password);
                users.add(newUser);
                UserManager.saveUsers(users);

                System.out.println(ANSI_GREEN + "[RegistrazioneRMI] Utente registrato: " + username + ANSI_RESET);
                return 100;
            }

        } catch (IOException e) {
            System.err.println(ANSI_RED + "[RegistrazioneRMI] Errore I/O: " + e.getMessage() + ANSI_RESET);
            return 103;
        } catch (Exception e) {
            throw new RemoteException(ANSI_RED + "[RegistrazioneRMI] Errore durante registrazione" + ANSI_RESET, e);
        }
    }
}
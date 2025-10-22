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

    public RegistrazioneRMIImpl() throws RemoteException {
        super();
        try {
            UserManager.initialize();
        } catch (Exception e) {
            throw new RemoteException("[RegistrazioneRMI] Errore inizializzazione servizio registrazione", e);
        }
    }

    public int register(String username, String password) throws RemoteException {
        try {
            System.out.println("[RegistrazioneRMI] Richiesta registrazione: " + username);

            // Controllo parametri
            if (username == null || username.trim().isEmpty()) {
                return 103;
            }

            if (password == null || password.trim().isEmpty()) {
                return 101;
            }

            synchronized (UserManager.getFileLock()) {
                JsonArray users = UserManager.loadUsers();

                // Verifico se username già esiste
                if (UserManager.findUser(users, username) != null) {
                    return 102;
                }

                JsonObject newUser = UserManager.createUser(username, password);
                users.add(newUser);
                UserManager.saveUsers(users);

                System.out.println("[RegistrazioneRMI] Utente registrato: " + username);
                return 100;
            }

        } catch (IOException e) {
            System.err.println("[RegistrazioneRMI] Errore I/O: " + e.getMessage());
            return 103;

        } catch (Exception e) {
            throw new RemoteException("[RegistrazioneRMI] Errore durante registrazione", e);
        }
    }
}
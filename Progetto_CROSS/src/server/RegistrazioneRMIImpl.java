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

            // Validazione con metodo dedicato
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 100) {
                return validationResult;
            }

            synchronized (UserManager.getFileLock()) {
                JsonArray users = UserManager.loadUsers();
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
package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import common.RegistrazioneRMI;
import server.utility.UserManager;

/**
 * Implementazione del servizio RMI per registrazione utenti nel sistema CROSS
 */
public class RegistrazioneRMIImpl extends UnicastRemoteObject implements RegistrazioneRMI {

    public RegistrazioneRMIImpl() throws RemoteException {
        super();
        try {
            UserManager.initialize();
        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore inizializzazione UserManager: " + e.getMessage());
            throw new RemoteException("Errore inizializzazione servizio registrazione", e);
        }
    }

    public int register(String username, String password) throws RemoteException {
        try {
            System.out.println("[RegistrazioneRMI] Richiesta registrazione per utente: " + username);

            // Validazione username
            if (username == null || username.trim().isEmpty()) {
                System.out.println("[RegistrazioneRMI] Registrazione fallita: username non valido");
                return 103;
            }

            // Validazione password
            if (password == null || password.trim().isEmpty()) {
                System.out.println("[RegistrazioneRMI] Registrazione fallita: password non valida");
                return 101;
            }

            synchronized (UserManager.getFileLock()) {

                // Carica lista utenti esistenti
                JsonArray users = UserManager.loadUsers();

                if (UserManager.findUser(users, username) != null) {
                    System.out.println("[RegistrazioneRMI] Registrazione fallita: username già esistente");
                    return 102;
                }

                // Crea nuovo oggetto utente
                JsonObject newUser = UserManager.createUser(username, password);

                // Aggiunge utente alla lista
                users.add(newUser);

                // Salva su file
                UserManager.saveUsers(users);

                System.out.println("[RegistrazioneRMI] Utente registrato con successo: " + username);
                return 100;
            }

        } catch (IOException e) {
            System.err.println("[RegistrazioneRMI] Errore I/O durante registrazione: " + e.getMessage());
            return 103;

        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore imprevisto durante registrazione: " + e.getMessage());
            e.printStackTrace();
            throw new RemoteException("Errore interno durante registrazione", e);
        }
    }
}
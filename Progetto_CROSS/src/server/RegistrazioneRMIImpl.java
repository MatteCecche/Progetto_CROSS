package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.RegistrazioneRMI;
import server.utility.UserManager;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementazione del servizio RMI per registrazione utenti nel sistema CROSS
 * - Implementazione dell'interfaccia RMI per registrazione remota
 * - Validazione parametri di registrazione
 * - Gestione errori RMI e codici di risposta standardizzati
 * - Inizializzazione del sistema di gestione utenti
 */

public class RegistrazioneRMIImpl extends UnicastRemoteObject implements RegistrazioneRMI {

    //Configura il servizio RMI e prepara il sistema di gestione utenti
    public RegistrazioneRMIImpl() throws RemoteException {
        super();
        try {
            UserManager.initialize();
        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore inizializzazione UserManager: " + e.getMessage());
            throw new RemoteException("Errore inizializzazione servizio registrazione", e);
        }
    }

    //Registra un nuovo utente nel sistema CROSS
    public int register(String username, String password) throws RemoteException {
        try {
            // Validazione parametri prima di acquisire il lock
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 100) {
                String errorMsg;
                switch (validationResult) {
                    case 101:
                        errorMsg = "password non valida";
                        break;
                    case 102:
                        errorMsg = "username già esistente";
                        break;
                    case 103:
                        errorMsg = "username non valido";
                        break;
                    default:
                        errorMsg = "errore sconosciuto";
                        break;
                }
                System.out.println("[RegistrazioneRMI] Registrazione fallita: " + errorMsg);
                return validationResult;
            }

            // Acquisisce lock per garantire atomicità delle operazioni
            ReentrantReadWriteLock lock = UserManager.getUsersLock();
            lock.writeLock().lock();
            try {
                // Controllo unicità username nel sistema
                if (UserManager.isUsernameExists(username)) {
                    System.out.println("[RegistrazioneRMI] Registrazione fallita: username già esistente");
                    return 102;
                }

                // Carica lista utenti esistenti dal sistema di persistenza JSON
                JsonArray users = UserManager.loadUsers();

                // Crea nuovo oggetto utente con username e password
                JsonObject newUser = UserManager.createUser(username, password);

                // Aggiunge utente alla lista in memoria
                users.add(newUser);

                // Salvataggio atomico della lista aggiornata su file JSON
                UserManager.saveUsers(users);

                System.out.println("[RegistrazioneRMI] Utente registrato con successo: " + username);
                return 100;

            } finally {
                // Rilascia sempre il lock, anche in caso di eccezione
                lock.writeLock().unlock();
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
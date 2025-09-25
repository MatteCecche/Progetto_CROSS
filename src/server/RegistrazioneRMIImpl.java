package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.utility.UserManager;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;


/**
 * Implementazione del servizio RMI per registrazione utenti nel sistema CROSS
 * - Implementazione dell'interfaccia RMI per registrazione remota
 * - Validazione parametri di registrazione
 * - Delegazione completa a UserManager per persistenza e logica utenti
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

    //Registra un nuovo utente
    public int register(String username, String password) throws RemoteException {
        try {
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 0) {
                String errorMsg;
                if (validationResult == 101) {
                    errorMsg = "password non valida";
                } else {
                    errorMsg = "username non valido";
                }
                System.out.println("[RegistrazioneRMI] Registrazione fallita: " + errorMsg);
                return validationResult;
            }

            // Controllo unicità username nel sistema
            if (UserManager.isUsernameExists(username)) {
                System.out.println("[RegistrazioneRMI] Registrazione fallita: username già esistente");
                return 102;
            }

            // Carica lista utenti esistenti dal sistema di persistenza JSON
            JsonArray users = UserManager.loadUsers();

            // Crea nuovo oggetto utente
            JsonObject newUser = UserManager.createUser(username, password);

            // Aggiunge utente alla lista in memoria
            users.add(newUser);

            // Salvataggio atomico della lista aggiornata su file JSON
            UserManager.saveUsers(users);

            System.out.println("[RegistrazioneRMI] Utente registrato con successo: " + username);
            return 100;

        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore durante registrazione: " + e.getMessage());
            return 103;
        }
    }
}
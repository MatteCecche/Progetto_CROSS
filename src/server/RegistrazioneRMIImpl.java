package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.utility.UserManager;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;

/**
 * Implementazione del servizio RMI per registrazione utenti
 * Gestisce la registrazione di nuovi utenti via RMI
 * Utilizza UserManager per tutte le operazioni sui dati degli utenti
 * Responsabilità:
 * - Interfaccia RMI per registrazione
 * - Delegazione a UserManager per persistenza
 *
 */
public class RegistrazioneRMIImpl extends RemoteObject implements RegistrazioneRMI {

    /**
     * Costruttore - inizializza l'oggetto remoto RMI
     * Inizializza il sistema di gestione utenti
     *
     * @throws RemoteException se errori nell'inizializzazione RMI
     */
    public RegistrazioneRMIImpl() throws RemoteException {
        super();

        try {
            // Inizializza il sistema di gestione utenti
            UserManager.initialize();
            System.out.println("[RegistrazioneRMI] Servizio registrazione RMI inizializzato");
        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore inizializzazione UserManager: " + e.getMessage());
            throw new RemoteException("Errore inizializzazione servizio registrazione", e);
        }
    }

    /**
     * Registra un nuovo utente nel sistema CROSS
     *
     * @param username nome utente (deve essere univoco)
     * @param password password dell'utente (non può essere vuota)
     * @return codice di risposta secondo le specifiche del progetto:
     *         100 - OK (registrazione avvenuta)
     *         101 - invalid password (password vuota)
     *         102 - username not available (username già esistente)
     *         103 - other error cases (errori generici)
     */
    @Override
    public int register(String username, String password) throws RemoteException {
        System.out.println("[RegistrazioneRMI] Richiesta registrazione per utente: " + username);

        try {
            // Validazione parametri di input usando UserManager
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 0) {
                String errorMsg = validationResult == 101 ? "password vuota" : "username vuoto";
                System.out.println("[RegistrazioneRMI] Registrazione fallita: " + errorMsg);
                return validationResult;
            }

            // Controlla se username già esiste
            if (UserManager.isUsernameExists(username)) {
                System.out.println("[RegistrazioneRMI] Registrazione fallita: username già esistente");
                return 102; // username not available
            }

            // Carica utenti esistenti
            JsonArray users = UserManager.loadUsers();

            // Crea nuovo utente
            JsonObject newUser = UserManager.createUser(username, password);

            // Aggiunge utente alla lista
            users.add(newUser);

            // Salva utenti aggiornati
            UserManager.saveUsers(users);

            System.out.println("[RegistrazioneRMI] Utente registrato con successo: " + username);
            return 100; // OK

        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore durante registrazione: " + e.getMessage());
            return 103; // other error cases
        }
    }
}
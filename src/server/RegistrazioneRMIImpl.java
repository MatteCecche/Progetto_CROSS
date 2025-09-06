package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.utility.UserManager;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;

/**
 * Implementazione del servizio RMI per registrazione utenti nel sistema CROSS
 * - Implementazione dell'interfaccia RMI per registrazione remota
 * - Validazione parametri di registrazione
 * - Delegazione completa a UserManager per persistenza e logica utenti
 * - Gestione errori RMI e codici di risposta standardizzati
 * - Inizializzazione del sistema di gestione utenti
 */
public class RegistrazioneRMIImpl extends RemoteObject implements RegistrazioneRMI {

    /**
     * Costruttore - inizializza l'oggetto remoto RMI
     * Configura il servizio RMI e prepara il sistema di gestione utenti
     *
     * @throws RemoteException se errori nell'inizializzazione RMI o del sistema utenti
     */
    public RegistrazioneRMIImpl() throws RemoteException {
        super();

        try {
            // Inizializza il sistema di gestione utenti
            UserManager.initialize();
            System.out.println("[RegistrazioneRMI] Servizio registrazione RMI inizializzato");
        } catch (Exception e) {
            System.err.println("[RegistrazioneRMI] Errore inizializzazione UserManager: " + e.getMessage());
            // Converte eccezione locale in RemoteException per protocollo RMI
            throw new RemoteException("Errore inizializzazione servizio registrazione", e);
        }
    }

    /**
     * Registra un nuovo utente nel sistema CROSS via chiamata RMI remota
     *
     * @param username nome utente scelto (deve essere univoco nel sistema)
     * @param password password scelta dall'utente (non può essere vuota)
     * @return codice di risposta numerico secondo le specifiche
     * @throws RemoteException se errori di comunicazione RMI
     */
    @Override
    public int register(String username, String password) throws RemoteException {
        System.out.println("[RegistrazioneRMI] Richiesta registrazione per utente: " + username);

        try {
            // Validazione parametri di input utilizzando logica centralizzata UserManager
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 0) {
                // Mappa codici di errore UserManager a messaggi descrittivi per logging
                String errorMsg = validationResult == 101 ? "password vuota" : "username vuoto";
                System.out.println("[RegistrazioneRMI] Registrazione fallita: " + errorMsg);
                return validationResult; // Ritorna direttamente il codice di errore
            }

            // Controllo unicità username nel sistema (prevenzione duplicati)
            if (UserManager.isUsernameExists(username)) {
                System.out.println("[RegistrazioneRMI] Registrazione fallita: username già esistente");
                return 102; // username not available
            }

            // Carica lista utenti esistenti dal sistema di persistenza
            JsonArray users = UserManager.loadUsers();

            // Crea nuovo oggetto utente con struttura conforme alle specifiche (username e password)
            JsonObject newUser = UserManager.createUser(username, password);

            // Aggiunge utente alla lista in memoria
            users.add(newUser);

            // Salvataggio atomico della lista aggiornata su file JSON
            UserManager.saveUsers(users);

            System.out.println("[RegistrazioneRMI] Utente registrato con successo: " + username);
            return 100; // OK - registrazione completata

        } catch (Exception e) {
            // Gestione di qualsiasi errore imprevisto
            System.err.println("[RegistrazioneRMI] Errore durante registrazione: " + e.getMessage());
            return 103; // errori generici di sistema
        }
    }
}
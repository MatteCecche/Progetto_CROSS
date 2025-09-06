package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.utility.UserManager;

import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;

/**
 * Implementazione del servizio RMI per registrazione utenti nel sistema CROSS
 * Gestisce la registrazione di nuovi utenti via RMI per studenti del vecchio ordinamento
 * secondo le specifiche del progetto che richiedono separazione tra registrazione (RMI)
 * e operazioni di login/gestione account (TCP).
 *
 * Questa classe estende RemoteObject per funzionare come oggetto remoto RMI
 * e implementa l'interfaccia RegistrazioneRMI per esporre il servizio di registrazione.
 *
 * Responsabilità principali:
 * - Implementazione dell'interfaccia RMI per registrazione remota
 * - Validazione parametri di registrazione secondo ALLEGATO 1
 * - Delegazione completa a UserManager per persistenza e logica utenti
 * - Gestione errori RMI e codici di risposta standardizzati
 * - Inizializzazione del sistema di gestione utenti
 *
 * Architettura:
 * - Thin layer che delega la logica business al UserManager
 * - Non gestisce direttamente I/O su file o strutture dati
 * - Focus su interfaccia RMI e gestione errori di rete
 * - Logging dettagliato per debugging e monitoring
 */
public class RegistrazioneRMIImpl extends RemoteObject implements RegistrazioneRMI {

    /**
     * Costruttore - inizializza l'oggetto remoto RMI
     * Configura il servizio RMI e prepara il sistema di gestione utenti
     *
     * L'inizializzazione include:
     * 1. Chiamata al costruttore della superclasse RemoteObject
     * 2. Inizializzazione del UserManager (filesystem, strutture dati)
     * 3. Setup logging per operazioni RMI
     * 4. Gestione errori con conversione a RemoteException
     *
     * Se l'inizializzazione fallisce, il servizio RMI non sarà disponibile
     * e il server principale dovrebbe terminare per evitare stati inconsistenti.
     *
     * @throws RemoteException se errori nell'inizializzazione RMI o del sistema utenti
     *         (filesystem non accessibile, UserManager non inizializzabile, etc.)
     */
    public RegistrazioneRMIImpl() throws RemoteException {
        super();

        try {
            // Inizializza il sistema di gestione utenti (filesystem, JSON, etc.)
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
     * Implementa il processo completo di registrazione secondo le specifiche dell'ALLEGATO 1
     *
     * Flusso di registrazione implementato:
     * 1. Logging della richiesta per auditing e debugging
     * 2. Validazione parametri tramite UserManager (null, empty, etc.)
     * 3. Controllo unicità username nel sistema esistente
     * 4. Caricamento lista utenti corrente dal sistema di persistenza
     * 5. Creazione nuovo oggetto utente con struttura conforme alle specifiche
     * 6. Aggiunta utente alla lista e salvataggio atomico
     * 7. Logging successo e ritorno codice OK
     *
     * Gestione errori robusta:
     * - Validazione: ritorno immediato con codice errore appropriato
     * - Username duplicato: controllo preventivo prima della creazione
     * - Eccezioni: catch generale con ritorno codice 103
     * - Logging dettagliato per debugging di problemi
     *
     * Codici di risposta conformi all'ALLEGATO 1:
     * - 100: OK - registrazione completata con successo
     * - 101: invalid password - password vuota o null (da UserManager.validateRegistrationParams)
     * - 102: username not available - username già presente nel sistema
     * - 103: other error cases - errori di I/O, sistema, o parametri non validi
     *
     * @param username nome utente scelto (deve essere univoco nel sistema)
     * @param password password scelta dall'utente (non può essere vuota secondo ALLEGATO 1)
     * @return codice di risposta numerico secondo le specifiche ALLEGATO 1
     * @throws RemoteException se errori di comunicazione RMI (rete, serializzazione, timeout)
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
                return 102; // username not available - conforme ALLEGATO 1
            }

            // Carica lista utenti esistenti dal sistema di persistenza
            JsonArray users = UserManager.loadUsers();

            // Crea nuovo oggetto utente con struttura conforme alle specifiche
            // (solo username e password, nessun campo aggiuntivo)
            JsonObject newUser = UserManager.createUser(username, password);

            // Aggiunge utente alla lista in memoria
            users.add(newUser);

            // Salvataggio atomico della lista aggiornata su file JSON
            UserManager.saveUsers(users);

            System.out.println("[RegistrazioneRMI] Utente registrato con successo: " + username);
            return 100; // OK - registrazione completata

        } catch (Exception e) {
            // Gestione catch-all per qualsiasi errore imprevisto
            System.err.println("[RegistrazioneRMI] Errore durante registrazione: " + e.getMessage());
            return 103; // other error cases - errori generici di sistema
        }
    }
}
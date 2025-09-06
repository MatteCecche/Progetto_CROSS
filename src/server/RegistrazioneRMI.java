package server;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interfaccia remota per il servizio di registrazione utenti via RMI
 * Definisce il contratto per la registrazione remota richiesta dalle specifiche
 * del vecchio ordinamento che richiedono RMI per le operazioni di registrazione.
 *
 * Questa interfaccia estende Remote per essere utilizzabile come oggetto remoto RMI
 * e permette ai client di registrare nuovi utenti nel sistema CROSS attraverso
 * chiamate remote invece che tramite connessioni TCP dirette.
 *
 * Separazione delle responsabilità:
 * - Registrazioni: gestite via RMI (questa interfaccia)
 * - Login e altre operazioni: gestite via TCP (ClientHandler)
 *
 * Conformità alle specifiche:
 * - Tutti i metodi devono dichiarare RemoteException per gestire errori di rete RMI
 * - Codici di risposta conformi all'ALLEGATO 1 del progetto
 * - Interfaccia minimal con solo operazioni di registrazione
 */
public interface RegistrazioneRMI extends Remote {

    /**
     * Registra un nuovo utente nel sistema CROSS via chiamata RMI remota
     * Implementa la registrazione secondo le specifiche dell'ALLEGATO 1
     *
     * Processo di registrazione (implementato dalla classe che implementa questa interfaccia):
     * 1. Validazione parametri di input (username e password non vuoti)
     * 2. Controllo unicità username nel sistema
     * 3. Creazione nuovo utente con struttura conforme alle specifiche
     * 4. Salvataggio nel sistema di persistenza (file JSON)
     * 5. Ritorno codice di stato secondo ALLEGATO 1
     *
     * La registrazione via RMI è obbligatoria per studenti del vecchio ordinamento
     * secondo le specifiche del progetto, mentre studenti nuovo ordinamento
     * possono utilizzare registrazione TCP.
     *
     * Codici di risposta conformi all'ALLEGATO 1:
     * - 100: OK - registrazione completata con successo
     * - 101: invalid password - password vuota o non valida
     * - 102: username not available - username già esistente nel sistema
     * - 103: other error cases - errori generici di sistema o parametri
     *
     * Requisiti di sicurezza:
     * - Username deve essere univoco in tutto il sistema
     * - Password non può essere vuota (secondo specifiche ALLEGATO 1)
     * - Gestione robusta degli errori per prevenire stati inconsistenti
     *
     * @param username nome utente scelto (deve essere univoco nel sistema)
     * @param password password scelta dall'utente (non può essere vuota)
     * @return codice di risposta numerico secondo le specifiche ALLEGATO 1
     * @throws RemoteException se errori di comunicazione RMI (rete, serializzazione,
     *         registry non raggiungibile, timeout, etc.)
     */
    int register(String username, String password) throws RemoteException;
}
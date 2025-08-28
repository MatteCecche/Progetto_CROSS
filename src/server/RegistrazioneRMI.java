package server;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interfaccia remota per il servizio di registrazione
 *
 * Gestisce la registrazione di nuovi utenti via RMI
 */
public interface RegistrazioneRMI extends Remote {

    /**
     * Registra un nuovo utente nel sistema
     *
     * @param username nome utente
     * @param password password dell'utente
     * @return codice di risposta:
     *         100 - OK (registrazione avvenuta)
     *         101 - invalid password (password vuota)
     *         102 - username not available (username già esistente)
     *         103 - other error cases (errori generici)
     * @throws RemoteException se errori di comunicazione RMI
     */
    int register(String username, String password) throws RemoteException;
}
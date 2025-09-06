package server;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interfaccia remota per il servizio di registrazione utenti via RMI
 */
public interface RegistrazioneRMI extends Remote {

    /**
     * Registra un nuovo utente nel sistema CROSS via chiamata RMI remota
     *
     * @param username nome utente scelto (deve essere univoco nel sistema)
     * @param password password scelta dall'utente (non può essere vuota)
     * @return codice di risposta numerico secondo le specifiche
     * @throws RemoteException se errori di comunicazione RMI
     */
    int register(String username, String password) throws RemoteException;
}
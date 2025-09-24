package server;

import java.rmi.Remote;
import java.rmi.RemoteException;


//Interfaccia remota per il servizio di registrazione utenti via RMI
public interface RegistrazioneRMI extends Remote {

    int register(String username, String password) throws RemoteException;

}
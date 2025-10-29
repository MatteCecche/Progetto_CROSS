package server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

import common.RegistrazioneRMI;
import server.utility.UserManager;
import server.utility.Colors;

public class RegistrazioneRMIImpl extends UnicastRemoteObject implements RegistrazioneRMI {

    public RegistrazioneRMIImpl() throws RemoteException {
        super();
        try {
            UserManager.initialize();
        } catch (Exception e) {
            throw new RemoteException(Colors.RED + "[RegistrazioneRMI] Errore inizializzazione servizio registrazione" + Colors.RESET, e);
        }
    }

    public int register(String username, String password) throws RemoteException {
        try {
            System.out.println(Colors.GREEN + "[RegistrazioneRMI] Richiesta registrazione: " + username + Colors.RESET);

            // Validazione con metodo dedicato
            int validationResult = UserManager.validateRegistrationParams(username, password);
            if (validationResult != 100) {
                return validationResult;
            }

            synchronized (UserManager.getFileLock()) {
                JsonArray users = UserManager.loadUsers();

                if (UserManager.findUser(users, username) != null) {
                    System.out.println(Colors.RED + "[RegistrazioneRMI] Utente già esistente: " + username + Colors.RESET);
                    return 102;
                }

                JsonObject newUser = UserManager.createUser(username, password);
                users.add(newUser);
                UserManager.saveUsers(users);

                System.out.println(Colors.GREEN + "[RegistrazioneRMI] Utente registrato: " + username + Colors.RESET);
                return 100;
            }

        } catch (IOException e) {
            System.err.println(Colors.RED + "[RegistrazioneRMI] Errore I/O: " + e.getMessage() + Colors.RESET);
            return 103;
        } catch (Exception e) {
            throw new RemoteException(Colors.RED + "[RegistrazioneRMI] Errore durante registrazione" + Colors.RESET, e);
        }
    }
}
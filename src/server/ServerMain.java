package server;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Classe Main del server CROSS
 * Implementa un server multithreaded che gestisce:
 * - Registrazioni via RMI
 * - Login e operazioni via TCP
 * - Thread pool per gestire client
 * - Thread per ascoltare comandi da terminale (chiusura)
 */
public class ServerMain {

    // Flag volatile per terminazione thread-safe del server
    private static volatile boolean running = true;

    // Socket principale per connessioni TCP dei client
    private static ServerSocket serverSocket;

    // Thread pool per gestire client multipli
    private static ExecutorService pool;


    public static void main(String[] args) {

        System.out.println("[Server] === Avvio Server CROSS ===");

        // Caricamento delle configurazioni da file properties
        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("[Server] Impossibile caricare configurazione: " + e.getMessage());
            System.exit(1);
            return; // Per il compilatore
        }

        // Parsing dei parametri di configurazione
        int tcpPort;
        int rmiPort;
        int socketTimeout;

        try {
            tcpPort = parseRequiredIntProperty(config, "TCP.port");
            rmiPort = parseRequiredIntProperty(config, "RMI.port");
            socketTimeout = parseRequiredIntProperty(config, "socket.timeout");
        } catch (IllegalArgumentException e) {
            System.err.println("[Server] Errore nel parsing dei parametri: " + e.getMessage());
            System.exit(1);
            return; // Per il compilatore
        }

        // Stampa configurazione caricata con successo
        System.out.println("[Server] Configurazione caricata correttamente:");
        System.out.println("[Server] - TCP Port: " + tcpPort);
        System.out.println("[Server] - RMI Port: " + rmiPort);
        System.out.println("[Server] - Socket Timeout: " + socketTimeout + "ms");

        // Avvio server RMI per registrazioni
        try {
            startRMIServer(rmiPort);
        } catch (Exception e) {
            System.err.println("[Server] Errore avvio RMI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Avvio server TCP principale
        try {
            startTCPServer(tcpPort, socketTimeout);
        } catch (IOException e) {
            System.err.println("[Server] Errore avvio TCP: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[Server] ERRORE IMPREVISTO durante avvio TCP: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            // Cleanup finale delle risorse
            shutdownServer();
        }
    }

    /**
     * Carica la configurazione dal file server.properties
     * Il server termina se non trova il file
     *
     * @return Properties oggetto con la configurazione caricata
     * @throws IOException se il file di configurazione non esiste o è illeggibile
     */
    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();

        File configFile = new File("src/server/server.properties");

        if (!configFile.exists()) {
            throw new IOException("File di configurazione non trovato: " + configFile.getPath());
        }

        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
            System.out.println("[Server] Configurazione caricata da: " + configFile.getPath());
        } catch (IOException e) {
            System.err.println("[Server] Errore caricamento configurazione: " + e.getMessage());
            throw e; // Rilancia l'eccezione - il server deve terminare
        }

        return prop;
    }

    /**
     * Effettua il parsing di un numero intero
     * Se la property manca o non è un numero valido, il server termina
     *
     * @param props oggetto Properties
     * @param key chiave da cercare
     * @return valore parsato
     * @throws IllegalArgumentException se la property manca o è invalida
     */
    private static int parseRequiredIntProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante nel file server.properties: " + key);
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valore non valido per " + key + ": " + value + " (deve essere un numero intero)");
        }
    }

    /**
     * Avvia il server RMI per gestire le registrazioni
     *
     * @param rmiPort porta su cui avviare il registry RMI
     * @throws Exception se errori durante setup RMI
     */
    private static void startRMIServer(int rmiPort) throws Exception {

        RegistrazioneRMIImpl rmiImpl = new RegistrazioneRMIImpl();
        LocateRegistry.createRegistry(rmiPort);
        Registry registry = LocateRegistry.getRegistry(rmiPort);
        registry.rebind("server-rmi", rmiImpl);

        System.out.println("[Server] Server RMI attivo sulla porta " + rmiPort);
    }

    /**
     * Avvia il server TCP principale per gestire connessioni client
     *
     * @param tcpPort porta TCP su cui mettersi in ascolto
     * @param socketTimeout timeout per i socket client
     * @throws IOException se errori nella creazione ServerSocket
     */
    private static void startTCPServer(int tcpPort, int socketTimeout) throws IOException {

        pool = Executors.newCachedThreadPool();
        System.out.println("[Server] Thread pool inizializzato (CachedThreadPool)");

        // Crea server socket per connessioni TCP
        serverSocket = new ServerSocket(tcpPort);
        System.out.println("[Server] Server TCP in ascolto sulla porta " + tcpPort);

        // Avvia thread separato per ascolto comandi da terminale
        Thread terminalListener = new Thread(() -> listenForTerminalCommands());
        terminalListener.setDaemon(true); // Thread daemon per non bloccare shutdown
        terminalListener.start();

        // Loop principale: accetta connessioni client
        while (running) {
            try {

                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(socketTimeout);

                System.out.println("[Server] Nuova connessione da: " +
                        clientSocket.getRemoteSocketAddress());

                // Crea handler per questo client e lo sottomette al thread pool
                // Ogni client viene gestito in un thread separato
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                pool.submit(clientHandler);

            } catch (IOException e) {
                if (running) {
                    System.err.println("[Server] Errore connessione TCP: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Thread per ascoltare comandi da terminale
     * Permette chiusura del server digitando "esci"
     */
    private static void listenForTerminalCommands() {
        Scanner scanner = new Scanner(System.in);

        while (running) {
            System.out.print("[Server] Digita 'esci' per terminare il server: ");

            try {
                String command = scanner.nextLine().trim().toLowerCase();

                if ("esci".equals(command)) {
                    System.out.println("[Server] Comando di arresto ricevuto...");

                    // Imposta flag per terminazione
                    running = false;

                    // Forza chiusura server socket per uscire da accept()
                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }

                    break;
                }
            } catch (Exception e) {
                System.err.println("[Server] Errore lettura comando: " + e.getMessage());
            }
        }

        scanner.close();
    }

    /**
     * Effettua chiusura del server
     * Chiude tutte le risorse in modo ordinato
     */
    private static void shutdownServer() {
        System.out.println("[Server] Chiusura del server in corso...");

        // Chiude server socket se ancora aperto
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                System.out.println("[Server] Server socket chiuso");
            } catch (IOException e) {
                System.err.println("[Server] Errore chiusura server socket: " + e.getMessage());
            }
        }

        // Chiusura del thread pool con timeout
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown(); // Non accetta nuovi task

            try {
                // Aspetta terminazione task attivi per 5 secondi
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.out.println("[Server] Chiusura forzata del thread pool...");
                    pool.shutdownNow(); // Forza terminazione
                }
                System.out.println("[Server] Thread pool terminato");

            } catch (InterruptedException e) {
                System.err.println("[Server] Interruzione durante chiusura thread pool");
                pool.shutdownNow();
            }
        }

        System.out.println("[Server] Server terminato correttamente.");
    }
}
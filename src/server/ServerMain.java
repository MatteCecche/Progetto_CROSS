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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Classe Main del server CROSS
 * Implementa un server multithreaded che gestisce:
 * - Registrazioni via RMI
 * - Login e operazioni via TCP
 * - Thread pool per gestire client multipli
 * - Thread per ascoltare comandi da terminale ("esci")
 * - Configurazione centralizzata tramite file properties
 * - Mappa socket-utente per gestione ottimizzata del login/logout
 */
public class ServerMain {

    // Flag volatile per terminazione thread-safe del server
    private static volatile boolean running = true;

    // Socket principale per connessioni TCP dei client
    private static ServerSocket serverSocket;

    // Thread pool per gestire client multipli
    private static ExecutorService pool;

    // Mappa thread-safe che associa socket dell'utente al suo username
    private static final ConcurrentHashMap<Socket, String> socketUserMap = new ConcurrentHashMap<>();

    /**
     * Main del server CROSS
     * 1. Caricamento configurazione da file properties
     * 2. Parsing e validazione parametri
     * 3. Avvio server RMI per registrazioni
     * 4. Avvio server TCP per operazioni client
     * 5. Gestione shutdown ordinato in caso di errori
     *
     * @param args argomenti da linea di comando
     */
    public static void main(String[] args) {

        System.out.println("[Server] ==== Avvio Server CROSS ====");

        // Caricamento delle configurazioni da file properties
        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("[Server] Impossibile caricare configurazione: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Parsing dei parametri di configurazione con gestione errori
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
            return;
        }

        // Stampa configurazione caricata con successo per debug
        System.out.println("[Server] Configurazione caricata correttamente:");
        System.out.println("[Server] - TCP Port: " + tcpPort);
        System.out.println("[Server] - RMI Port: " + rmiPort);
        System.out.println("[Server] - Socket Timeout: " + socketTimeout + "ms");

        // Avvio server RMI per registrazioni
        try {
            startRMIServer(rmiPort);
            OrderManager.initialize();
        } catch (Exception e) {
            System.err.println("[Server] Errore avvio connessione RMI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Avvio server TCP principale per tutte le operazioni client
        try {
            startTCPServer(tcpPort, socketTimeout);
        } catch (IOException e) {
            System.err.println("[Server] Errore avvio connessione TCP: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("[Server] ERRORE IMPREVISTO durante avvio connessione TCP: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            shutdownServer();
        }
    }

    /**
     * Carica la configurazione dal file server.properties
     *
     * @return Properties oggetto con la configurazione caricata e validata
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
            throw e;
        }

        return prop;
    }

    /**
     * Effettua il parsing di un numero intero da Properties
     *
     * @param props oggetto Properties contenente la configurazione
     * @param key chiave da cercare nel file properties
     * @return valore parsato come intero
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
     * Avvia il server RMI per gestire le registrazioni utenti
     * Segue il pattern delle slide RMI per setup corretto con UnicastRemoteObject
     *
     * IMPORTANTE: Dal momento che RegistrazioneRMIImpl estende già UnicastRemoteObject,
     * NON è necessario chiamare exportObject() - l'export avviene automaticamente
     * nel costruttore di UnicastRemoteObject
     *
     * @param rmiPort porta su cui avviare il registry RMI
     * @throws Exception se errori durante setup RMI (registry, binding)
     */
    private static void startRMIServer(int rmiPort) throws Exception {

        // Step 1: Istanzia l'oggetto remoto (secondo slide RMI)
        // Il costruttore di RegistrazioneRMIImpl chiama super() di UnicastRemoteObject
        // che esporta automaticamente l'oggetto creando lo stub
        RegistrazioneRMIImpl rmiImpl = new RegistrazioneRMIImpl();

        // Step 2: Crea il registry sulla porta specificata (secondo slide)
        LocateRegistry.createRegistry(rmiPort);

        // Step 3: Ottiene riferimento al registry appena creato
        Registry registry = LocateRegistry.getRegistry(rmiPort);

        // Step 4: Registra il servizio nel registry con nome "server-rmi"
        // Non serve cast perché RegistrazioneRMIImpl implementa già RegistrazioneRMI
        // e UnicastRemoteObject gestisce automaticamente lo stub
        registry.rebind("server-rmi", rmiImpl);

        System.out.println("[Server] Server RMI attivo sulla porta " + rmiPort);
        System.out.println("[Server] Servizio registrazione disponibile come 'server-rmi'");
    }

    /**
     * Avvia il server TCP principale per gestire connessioni client
     * Coordina thread pool, listener terminale e loop di accettazione connessioni
     * Ogni client viene gestito in un thread separato dal pool
     *
     * @param tcpPort porta TCP su cui mettersi in ascolto
     * @param socketTimeout timeout per i socket client in millisecondi
     * @throws IOException se errori nella creazione ServerSocket
     */
    private static void startTCPServer(int tcpPort, int socketTimeout) throws IOException {

        pool = Executors.newCachedThreadPool();
        System.out.println("[Server] Thread pool inizializzato (CachedThreadPool)");

        serverSocket = new ServerSocket(tcpPort);
        System.out.println("[Server] Server TCP in ascolto sulla porta " + tcpPort);

        // Avvia thread separato per ascolto comandi da terminale
        Thread terminalListener = new Thread(new Runnable() {
            @Override
            public void run() {
                listenForTerminalCommands();
            }
        });
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
                // Passa la mappa condivisa per gestione login/logout
                ClientHandler clientHandler = new ClientHandler(clientSocket, socketUserMap);
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
     * Permette chiusura ordinata del server digitando "esci"
     */
    private static void listenForTerminalCommands() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("[Server] Digita 'esci' per terminare il server");

        while (running) {
            try {
                String command = scanner.nextLine().trim().toLowerCase();

                if ("esci".equals(command)) {
                    System.out.println("[Server] Comando di arresto ricevuto...");

                    // Imposta flag per terminazione thread-safe
                    running = false;

                    // Forza chiusura server socket per uscire da accept()
                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }

                    break;
                } else if (!command.isEmpty()) {
                    System.out.println("[Server] Comando non riconosciuto: " + command);
                    System.out.println("[Server] Digita 'esci' per terminare il server");
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Server] Errore lettura comando: " + e.getMessage());
                }
                // Se c'è un errore nell'input, termina il thread
                break;
            }
        }

        // Chiude scanner locale del thread
        scanner.close();

        // Forza chiusura del server se non già fatto
        if (running) {
            running = false;
        }
    }

    /**
     * Effettua chiusura ordinata del server
     * Garantisce terminazione pulita di tutte le risorse allocate.
     */
    private static void shutdownServer() {
        System.out.println("[Server] Chiusura del server in corso...");

        // Imposta flag di terminazione per tutti i thread
        running = false;

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
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("[Server] Server terminato correttamente.");
        System.exit(0);
    }
}
package server;

import server.utility.UserManager;

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
import java.util.concurrent.ScheduledExecutorService;

/**
 * Classe Main del server CROSS
 * Implementa un server multithreaded che gestisce:
 * - Registrazioni via RMI
 * - Login e operazioni via TCP
 * - Thread pool per gestire client
 * - Servizio multicast per notifiche prezzo
 * - Thread per ascoltare il comando "esci" da terminale
 * - Mappa socket-utente
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

    // Thread separato per ascoltare comandi da terminale
    private static Thread terminalListener;

    private static ScheduledExecutorService persistenceScheduler;


    public static void main(String[] args) {

        System.out.println("[Server] ===== Avvio Server CROSS =====");

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
        String multicastAddress;
        int multicastPort;

        // Lettura parametri
        try {
            tcpPort = parseRequiredIntProperty(config, "TCP.port");
            rmiPort = parseRequiredIntProperty(config, "RMI.port");
            socketTimeout = parseRequiredIntProperty(config, "socket.timeout");
            multicastAddress = parseRequiredStringProperty(config, "multicast.address");
            multicastPort = parseRequiredIntProperty(config, "multicast.port");
        } catch (IllegalArgumentException e) {
            System.err.println("[Server] Errore nel parsing dei parametri: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Avvio server RMI per registrazioni
        try {
            startRMIServer(rmiPort);
            OrderManager.initialize();

            // Avvio persistenza periodica (ogni 5 minuti secondo best practice)
            int persistenceInterval = 5; // minuti
            startPeriodicPersistence(persistenceInterval);

            // Inizializzazione servizio multicast per notifiche prezzo
            PriceNotificationService.initialize(multicastAddress, multicastPort);

            // Inizializzazione servizio UDP per notifiche trade
            UDPNotificationService.initialize();

        } catch (Exception e) {
            System.err.println("[Server] Errore avvio servizi: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Avvio server TCP principale per tutte le operazioni client
        try {
            startTCPServer(tcpPort, socketTimeout);
        } catch (Exception e) {
            System.err.println("Errore avvio server TCP: " + e.getMessage());
            System.exit(1);
        } finally {
            shutdownServer();
        }
    }

    //Avvia il servizio di persistenza periodica per utenti e trade
    private static void startPeriodicPersistence(int intervalMinutes) {
        persistenceScheduler = Executors.newScheduledThreadPool(1);

        System.out.println("[Server] Avvio persistenza periodica (intervallo: " + intervalMinutes + " minuti)");

        // Schedulazione task di persistenza periodica
        persistenceScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println("[Server] === Inizio persistenza periodica ===");

                    // Salva tutti gli utenti registrati
                    UserManager.saveAllUsers();
                    System.out.println("[Server] ✓ Utenti salvati correttamente");

                    // I trade vengono già salvati automaticamente in TradePersistence.saveTrade()
                    // ma possiamo forzare un flush se necessario
                    System.out.println("[Server] ✓ Trade già persistiti automaticamente");

                    System.out.println("[Server] === Persistenza periodica completata ===");

                } catch (Exception e) {
                    System.err.println("[Server] ✗ Errore durante persistenza periodica: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);

        System.out.println("[Server] Servizio di persistenza periodica avviato con successo");
    }

    // Carica la configurazione dal file server.properties
    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();
        File configFile = new File("src/server/server.properties");
        if (!configFile.exists()) {
            throw new IOException("File di configurazione non trovato: " + configFile.getPath());
        }
        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
        } catch (IOException e) {
            System.err.println("[Server] Errore caricamento configurazione: " + e.getMessage());
            throw e;
        }

        return prop;
    }

    //Effettua il parsing di un numero intero da Properties
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

    //Effettua il parsing di una stringa da Properties
    private static String parseRequiredStringProperty(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante nel file server.properties: " + key);
        }

        return value.trim();
    }

    //Avvia il server RMI per gestire le registrazioni utenti
    private static void startRMIServer(int rmiPort) throws Exception {
        RegistrazioneRMIImpl rmiImpl = new RegistrazioneRMIImpl();
        LocateRegistry.createRegistry(rmiPort);
        Registry registry = LocateRegistry.getRegistry(rmiPort);
        registry.rebind("server-rmi", rmiImpl);
    }

    //Avvia il server TCP principale per gestire connessioni client
    private static void startTCPServer(int tcpPort, int socketTimeout) throws IOException {
        pool = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(tcpPort);
        serverSocket.setSoTimeout(1000); // 1 secondo di timeout

        // Avvia thread separato per ascolto comandi da terminale
        terminalListener = new Thread(new Runnable() {
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
                ClientHandler clientHandler = new ClientHandler(clientSocket, socketUserMap);
                pool.submit(clientHandler);
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Server] Errore accettazione connessione client: " + e.getMessage());
                }
            }
        }
    }


    //Thread separato per ascoltare il comando "esci" da terminale
    private static void listenForTerminalCommands() {
        Scanner terminalScanner = new Scanner(System.in);
        System.out.println("[Server] Digitare 'esci' per terminare il server");
        while (running) {
            try {
                String command = terminalScanner.nextLine().trim().toLowerCase();
                if ("esci".equals(command)) {
                    System.out.println("[Server] Comando di terminazione ricevuto");
                    running = false;
                    try {
                        if (serverSocket != null && !serverSocket.isClosed()) {
                            serverSocket.close();
                        }
                    } catch (IOException e) {
                        System.err.println("[Server] Errore chiusura: " + e.getMessage());
                    }
                    break;
                } else if (!command.isEmpty()) {
                    System.out.println("[Server] Comando non riconosciuto. Digitare 'esci' per terminare il server");
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Server] Errore lettura comando terminale: " + e.getMessage());
                }
            }
        }

        terminalScanner.close();
        System.out.println("[Server] Listener comandi terminale terminato");
    }

    //Esegue lo shutdown ordinato del server
    private static void shutdownServer() {
        try {
            System.out.println("[Server] Iniziando shutdown ordinato del server...");
            running = false;

            System.out.println("[Server] Esecuzione persistenza finale prima dello shutdown...");
            try {
                UserManager.saveAllUsers();
                System.out.println("[Server] ✓ Persistenza finale utenti completata");
            } catch (Exception e) {
                System.err.println("[Server] ✗ Errore persistenza finale: " + e.getMessage());
            }

            if (persistenceScheduler != null) {
                System.out.println("[Server] Chiusura servizio persistenza periodica...");
                persistenceScheduler.shutdown();
                try {
                    if (!persistenceScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                        persistenceScheduler.shutdownNow();
                        System.out.println("[Server] Persistenza scheduler forzata la chiusura");
                    }
                } catch (InterruptedException e) {
                    persistenceScheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                System.out.println("[Server] ✓ Servizio persistenza chiuso");
            }

            // Attende terminazione del thread listener terminale
            if (terminalListener != null && terminalListener.isAlive()) {
                try {
                    terminalListener.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Server] Interruzione durante join del thread terminale");
                }
            }

            // Shutdown thread pool client handlers
            if (pool != null) {
                System.out.println("[Server] Chiusura thread pool...");
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                System.out.println("[Server] ✓ Thread pool chiuso");
            }

            // Chiusura socket server
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                System.out.println("[Server] ✓ Server socket chiuso");
            }

            // Chiusura servizio multicast
            PriceNotificationService.shutdown();

            // Chiusura servizio notifica UDP
            UDPNotificationService.shutdown();

            // Pulizia mappa socket-utente
            socketUserMap.clear();

            System.out.println("[Server] ===== Shutdown completato con successo =====");
            System.exit(0);

        } catch (Exception e) {
            System.err.println("[Server] Errore durante shutdown: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
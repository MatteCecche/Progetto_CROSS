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
import java.util.concurrent.ScheduledExecutorService;

import server.utility.UserManager;

/*
 * Server principale del sistema CROSS
 */
public class ServerMain {

    // Flag volatile per terminazione del server
    private static volatile boolean running = true;

    // Socket principale per connessioni TCP dei client
    private static ServerSocket serverSocket;

    // Thread pool per gestire i client
    private static ExecutorService pool;

    // Associazione socket dell'utente al suo username
    private static final ConcurrentHashMap<Socket, String> socketUserMap = new ConcurrentHashMap<>();

    // Thread separato per ascoltare comandi da terminale
    private static Thread terminalListener;

    // Esecuzione periodica del salvataggio dati
    private static ScheduledExecutorService persistenceScheduler;


    public static void main(String[] args) {
        System.out.println("[Server] ===== Avvio Server CROSS ======");

        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println("[Server] Impossibile caricare configurazione: " + e.getMessage());
            System.exit(1);
            return;
        }

        int tcpPort;
        int rmiPort;
        int socketTimeout;
        String multicastAddress;
        int multicastPort;

        try {
            tcpPort = parseRequiredIntProperty(config, "TCP.port");
            rmiPort = parseRequiredIntProperty(config, "RMI.port");
            socketTimeout = parseRequiredIntProperty(config, "socket.timeout");
            multicastAddress = parseRequiredStringProperty(config, "multicast.address");
            multicastPort = parseRequiredIntProperty(config, "multicast.port");
        } catch (IllegalArgumentException e) {
            System.err.println("[Server] Errore parsing configurazione: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            startRMIServer(rmiPort);
            OrderManager.initialize();

            // Persistenza ogni minuto
            startPeriodicPersistence(1);

            PriceNotificationService.initialize(multicastAddress, multicastPort);
            UDPNotificationService.initialize();

        } catch (Exception e) {
            System.err.println("[Server] Errore avvio servizi: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        try {
            startTCPServer(tcpPort, socketTimeout);
        } catch (Exception e) {
            System.err.println("[Server] Errore avvio TCP: " + e.getMessage());
            System.exit(1);
        } finally {
            shutdownServer();
        }
    }

    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();
        File configFile = new File("src/server/server.properties");

        if (!configFile.exists()) {
            throw new IOException("File configurazione non trovato: " + configFile.getPath());
        }

        try (FileReader reader = new FileReader(configFile)) {
            prop.load(reader);
        }

        return prop;
    }

    private static void startRMIServer(int rmiPort) throws Exception {
        RegistrazioneRMIImpl rmiImpl = new RegistrazioneRMIImpl();
        LocateRegistry.createRegistry(rmiPort);
        Registry registry = LocateRegistry.getRegistry(rmiPort);
        registry.rebind("server-rmi", rmiImpl);
    }

    // ✅ CORRETTO - Lambda expression
    private static void startPeriodicPersistence(int intervalMinutes) {
        persistenceScheduler = Executors.newScheduledThreadPool(1);

        persistenceScheduler.scheduleAtFixedRate(() -> {
            try {
                UserManager.saveAllUsers();
            } catch (Exception e) {
                System.err.println("[Server] Errore persistenza: " + e.getMessage());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    private static void startTCPServer(int tcpPort, int socketTimeout) throws IOException {
        pool = Executors.newCachedThreadPool();
        serverSocket = new ServerSocket(tcpPort);
        serverSocket.setSoTimeout(1000);

        terminalListener = new Thread(() -> listenForTerminalCommands());
        terminalListener.start();

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(socketTimeout);

                System.out.println("[Server] Nuova connessione: " +
                        clientSocket.getRemoteSocketAddress());

                ClientHandler handler = new ClientHandler(clientSocket, socketUserMap);
                pool.submit(handler);

            } catch (java.net.SocketTimeoutException e) {
                // non fare nulla
            } catch (IOException e) {
                if (running) {
                    System.err.println("[Server] Errore connessione client: " + e.getMessage());
                }
            }
        }
    }

    private static void listenForTerminalCommands() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("[Server] Digitare 'esci' per terminare");

        while (running) {
            try {
                String command = scanner.nextLine().trim().toLowerCase();

                if ("esci".equals(command)) {
                    System.out.println("[Server] Terminazione in corso...");
                    running = false;

                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }
                    break;

                } else if (!command.isEmpty()) {
                    System.out.println("[Server] Comando non riconosciuto");
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[Server] Errore lettura comando: " + e.getMessage());
                }
            }
        }

        scanner.close();
    }

    private static void shutdownServer() {
        try {
            running = false;

            // Salvataggio finale
            try {
                UserManager.saveAllUsers();
                System.out.println("[Server] Dati salvati");
            } catch (Exception e) {
                System.err.println("[Server] Errore salvataggio: " + e.getMessage());
            }

            // Chiusura scheduler persistenza
            if (persistenceScheduler != null) {
                persistenceScheduler.shutdown();
                if (!persistenceScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    persistenceScheduler.shutdownNow();
                }
            }

            // Attesa thread terminale
            if (terminalListener != null && terminalListener.isAlive()) {
                terminalListener.join(2000);
            }

            // Chiusura thread pool
            if (pool != null) {
                pool.shutdown();
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            }

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            PriceNotificationService.shutdown();
            UDPNotificationService.shutdown();
            socketUserMap.clear();

            System.out.println("[Server] ==== Chiusura Server CROSS ====");
            System.exit(0);

        } catch (Exception e) {
            System.err.println("[Server] Errore shutdown: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int parseRequiredIntProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante: " + key);
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valore non valido per " + key + ": " + value);
        }
    }

    private static String parseRequiredStringProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Parametro mancante: " + key);
        }

        return value.trim();
    }
}
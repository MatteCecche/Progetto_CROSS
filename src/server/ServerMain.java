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
import server.utility.Colors;

public class ServerMain {

    private static volatile boolean running = true;
    private static ServerSocket serverSocket;
    private static ExecutorService pool;
    private static final ConcurrentHashMap<Socket, String> socketUserMap = new ConcurrentHashMap<>();
    private static Thread terminalListener;
    private static ScheduledExecutorService persistenceScheduler;


    public static void main(String[] args) {
        System.out.println();
        System.out.println(Colors.CYAN + "==================== Avvio Server CROSS =====================" + Colors.RESET);

        Properties config;
        try {
            config = loadConfiguration();
        } catch (IOException e) {
            System.err.println(Colors.RED + "[Server] Impossibile caricare configurazione: " + e.getMessage() + Colors.RESET);
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
            System.err.println(Colors.RED + "[Server] Errore parsing configurazione: " + e.getMessage() + Colors.RESET);
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
            System.err.println(Colors.RED + "[Server] Errore avvio servizi: " + e.getMessage() + Colors.RESET);
            e.printStackTrace();
            System.exit(1);
        }

        try {
            startTCPServer(tcpPort, socketTimeout);
        } catch (Exception e) {
            System.err.println(Colors.RED + "[Server] Errore avvio TCP: " + e.getMessage() + Colors.RESET);
            System.exit(1);
        } finally {
            shutdownServer();
        }
    }

    private static Properties loadConfiguration() throws IOException {
        Properties prop = new Properties();
        File configFile = new File("src/server/server.properties");

        if (!configFile.exists()) {
            throw new IOException(Colors.RED + "File configurazione non trovato: " + configFile.getPath() + Colors.RESET);
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

    private static void startPeriodicPersistence(int intervalMinutes) {
        persistenceScheduler = Executors.newScheduledThreadPool(1);

        persistenceScheduler.scheduleAtFixedRate(() -> {
            try {
                UserManager.saveAllUsers();
            } catch (Exception e) {
                System.err.println(Colors.RED + "[Server] Errore persistenza: " + e.getMessage() + Colors.RESET);
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

                System.out.println(Colors.GREEN + "[Server] Nuova connessione: " + clientSocket.getRemoteSocketAddress() + Colors.RESET);

                ClientHandler handler = new ClientHandler(clientSocket, socketUserMap);
                pool.submit(handler);

            } catch (java.net.SocketTimeoutException e) {
            } catch (IOException e) {
                if (running) {
                    System.err.println(Colors.RED + "[Server] Errore connessione client: " + e.getMessage() + Colors.RESET);
                }
            }
        }
    }

    private static void listenForTerminalCommands() {
        Scanner scanner = new Scanner(System.in);
        System.out.println(Colors.CYAN + "=============== Digitare 'esci' per terminare ===============" + Colors.RESET);

        while (running) {
            try {
                String command = scanner.nextLine().trim().toLowerCase();

                if ("esci".equals(command)) {
                    running = false;

                    if (serverSocket != null && !serverSocket.isClosed()) {
                        serverSocket.close();
                    }
                    break;

                } else if (!command.isEmpty()) {
                    System.out.println(Colors.RED + "[Server] Comando non riconosciuto" + Colors.RESET);
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println(Colors.RED + "[Server] Errore lettura comando: " + e.getMessage() + Colors.RESET);
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
                System.out.println(Colors.GREEN + "[Server] Dati salvati" + Colors.RESET);
            } catch (Exception e) {
                System.err.println(Colors.RED + "[Server] Errore salvataggio: " + e.getMessage() + Colors.RESET);
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

            System.out.println(Colors.CYAN + "=================== Chiusura Server CROSS ===================" + Colors.RESET);
            System.out.println();
            System.exit(0);

        } catch (Exception e) {
            System.err.println(Colors.RED + "[Server] Errore shutdown: " + e.getMessage() + Colors.RESET);
            System.exit(1);
        }
    }

    private static int parseRequiredIntProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(Colors.RED + "Parametro mancante: " + key + Colors.RESET);
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(Colors.RED + "Valore non valido per " + key + ": " + value + Colors.RESET);
        }
    }

    private static String parseRequiredStringProperty(Properties props, String key) {
        String value = props.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(Colors.RED + "Parametro mancante: " + key + Colors.RESET);
        }

        return value.trim();
    }
}
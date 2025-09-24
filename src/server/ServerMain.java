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

            // Inizializzazione servizio multicast per notifiche prezzo
            PriceNotificationService.initialize(multicastAddress, multicastPort);
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
            running = false;

            // Attende terminazione del thread listener terminale
            if (terminalListener != null && terminalListener.isAlive()) {
                try {
                    // Aspetta terminazione thread terminale
                    terminalListener.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("[Server] Interruzione durante join del thread terminale");
                }
            }
            if (pool != null) {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            PriceNotificationService.shutdown();
            socketUserMap.clear();
            System.out.println("[Server] Shutdown completato");
            System.exit(0);
        } catch (Exception e) {
            System.err.println("[Server] Errore durante shutdown: " + e.getMessage());
        }
    }
}
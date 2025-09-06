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
 * Classe Main del server CROSS (Cryptocurrency Online Stock Simulator)
 * Implementa un server multithreaded che gestisce:
 * - Registrazioni via RMI (per studenti vecchio ordinamento)
 * - Login e operazioni via TCP (connessioni persistenti)
 * - Thread pool per gestire client multipli concorrentemente
 * - Thread per ascoltare comandi da terminale (chiusura ordinata)
 * - Configurazione centralizzata tramite file properties
 * - Mappa socket-utente per gestione ottimizzata del login/logout
 */
public class ServerMain {

    // Flag volatile per terminazione thread-safe del server
    // Volatile garantisce visibilità immediata tra thread
    private static volatile boolean running = true;

    // Socket principale per connessioni TCP dei client
    private static ServerSocket serverSocket;

    // Thread pool per gestire client multipli concorrentemente
    // CachedThreadPool si adatta automaticamente al carico
    private static ExecutorService pool;

    // Mappa thread-safe che associa socket dell'utente al suo username
    // Utilizzata per gestione efficiente login/logout senza file I/O
    private static final ConcurrentHashMap<Socket, String> socketUserMap = new ConcurrentHashMap<>();

    /**
     * Main del server CROSS - Punto di ingresso dell'applicazione
     * Orchestratore principale che coordina l'avvio di tutti i servizi:
     * 1. Caricamento configurazione da file properties
     * 2. Parsing e validazione parametri
     * 3. Avvio server RMI per registrazioni
     * 4. Avvio server TCP per operazioni client
     * 5. Gestione shutdown ordinato in caso di errori
     *
     * @param args argomenti da linea di comando (attualmente non utilizzati)
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
            return; // Per sicurezza del compilatore
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
            return; // Per sicurezza del compilatore
        }

        // Stampa configurazione caricata con successo per debug
        System.out.println("[Server] Configurazione caricata correttamente:");
        System.out.println("[Server] - TCP Port: " + tcpPort);
        System.out.println("[Server] - RMI Port: " + rmiPort);
        System.out.println("[Server] - Socket Timeout: " + socketTimeout + "ms");

        // Avvio server RMI per registrazioni (richiesto per vecchio ordinamento)
        try {
            startRMIServer(rmiPort);
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
            // Cleanup finale delle risorse garantito anche in caso di eccezioni
            shutdownServer();
        }
    }

    /**
     * Carica la configurazione dal file server.properties
     * Legge i parametri di configurazione dal filesystem
     * Il server termina se non trova il file per evitare configurazioni errate
     *
     * Parametri attesi nel file properties:
     * - TCP.port: porta per connessioni client
     * - RMI.port: porta per registry RMI
     * - socket.timeout: timeout connessioni client in millisecondi
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
            throw e; // Rilancia l'eccezione - il server deve terminare
        }

        return prop;
    }

    /**
     * Effettua il parsing di un numero intero da Properties
     * Valida che la property esista e sia un numero intero valido
     * Se la property manca o non è un numero valido, il server termina
     *
     * Garantisce che tutti i parametri numerici siano validi prima dell'avvio
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
     * Avvia il server RMI per gestire le registrazioni
     * Richiesto dalle specifiche per studenti del vecchio ordinamento
     * Il RegistrazioneRMIImpl inizializzerà automaticamente il UserManager
     *
     * Processo di setup RMI:
     * 1. Crea implementazione del servizio remoto
     * 2. Esporta l'oggetto come stub RMI
     * 3. Crea registry RMI sulla porta specificata
     * 4. Registra il servizio con nome simbolico
     *
     * @param rmiPort porta su cui avviare il registry RMI
     * @throws Exception se errori durante setup RMI (registry, binding, export)
     */
    private static void startRMIServer(int rmiPort) throws Exception {

        RegistrazioneRMIImpl rmiImpl = new RegistrazioneRMIImpl();
        RegistrazioneRMI stub = (RegistrazioneRMI) java.rmi.server.UnicastRemoteObject.exportObject(rmiImpl, 0);
        LocateRegistry.createRegistry(rmiPort);
        Registry registry = LocateRegistry.getRegistry(rmiPort);
        registry.rebind("server-rmi", stub);

        System.out.println("[Server] Server RMI attivo sulla porta " + rmiPort);
    }

    /**
     * Avvia il server TCP principale per gestire connessioni client
     * Coordina thread pool, listener terminale e loop di accettazione connessioni
     * Ogni client viene gestito in un thread separato dal pool
     *
     * Componenti gestiti:
     * - CachedThreadPool per scalabilità automatica
     * - ServerSocket con porta configurabile
     * - Thread daemon per comandi terminale
     * - Loop di accettazione con gestione errori
     * - Timeout configurabile per socket client
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
     * Implementa interfaccia amministrativa per controllo server
     * Permette chiusura ordinata del server digitando "esci"
     * Thread daemon che termina automaticamente con il processo principale
     *
     * Comandi supportati:
     * - "esci": terminazione ordinata del server
     * - altri comandi: messaggio di errore e help
     *
     * Utilizza prompt ">>" per coerenza con interfaccia client
     */
    private static void listenForTerminalCommands() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("[Server] Digita 'esci' per terminare il server");

        while (running) {
            System.out.print(">> ");
            System.out.flush(); // Forza la stampa del prompt

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
            }
        }

        scanner.close();
    }

    /**
     * Effettua chiusura ordinata del server
     * Garantisce terminazione pulita di tutte le risorse:
     * - ServerSocket per fermare nuove connessioni
     * - Thread pool con timeout per client attivi
     * - Cleanup generale e terminazione processo
     *
     * Sequenza di shutdown:
     * 1. Chiusura ServerSocket (ferma nuove connessioni)
     * 2. Shutdown graceful del thread pool (5 secondi timeout)
     * 3. Shutdown forzato se necessario
     * 4. Terminazione processo con System.exit()
     *
     * Garantisce che il server termini senza leak di risorse
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
        System.exit(0);
    }
}
package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

/**
 * Thread listener per ricevere notifiche multicast dal server CROSS
 * Implementa la ricezione di notifiche di soglia prezzo secondo le specifiche del vecchio ordinamento
 *
 * Funziona come thread separato che ascolta continuamente messaggi multicast UDP
 * dal server quando vengono superate soglie di prezzo BTC registrate dall'utente
 *
 * Implementa il pattern delle slide multicast: MulticastSocket.joinGroup(), receive()
 */
public class MulticastListener implements Runnable {

    // Configurazione multicast (deve coincidere con il server)
    private final String multicastAddress;
    private final int multicastPort;

    // Socket multicast per ricezione messaggi
    private MulticastSocket multicastSocket;
    private InetAddress group;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

    // Username dell'utente per filtrare notifiche pertinenti
    private final String username;

    /**
     * Costruttore del listener multicast
     *
     * @param multicastAddress indirizzo del gruppo multicast (es: "230.0.0.1")
     * @param multicastPort porta del gruppo multicast (es: 9876)
     * @param username nome utente per filtrare le notifiche
     */
    public MulticastListener(String multicastAddress, int multicastPort, String username) {
        this.multicastAddress = multicastAddress;
        this.multicastPort = multicastPort;
        this.username = username;
        this.running = false;
    }

    /**
     * Avvia il listener multicast
     * Crea socket, si unisce al gruppo multicast e inizia l'ascolto
     *
     * @throws Exception se errori nell'inizializzazione multicast
     */
    public void start() throws Exception {
        try {
            // Crea MulticastSocket sulla porta specifica seguendo le slide
            multicastSocket = new MulticastSocket(multicastPort);

            // Ottiene riferimento al gruppo multicast
            group = InetAddress.getByName(multicastAddress);

            // Si unisce al gruppo multicast (joinGroup dalle slide)
            multicastSocket.joinGroup(group);

            running = true;

            System.out.println("[MulticastListener] Listener avviato - Gruppo: " +
                    multicastAddress + ":" + multicastPort);
            System.out.println("[MulticastListener] In ascolto per notifiche prezzo utente: " + username);

        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore avvio listener multicast: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Main loop del thread - ascolta messaggi multicast in continuazione
     * Implementa il pattern receive() delle slide multicast
     */
    @Override
    public void run() {
        byte[] buffer = new byte[1024]; // Buffer per messaggi multicast

        System.out.println("[MulticastListener] Thread listener avviato");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Prepara pacchetto per ricezione (pattern delle slide)
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Riceve messaggio multicast (bloccante)
                multicastSocket.receive(packet);

                // Estrae messaggio dal pacchetto
                String receivedMessage = new String(packet.getData(), 0, packet.getLength(), "UTF-8");

                // Processa il messaggio ricevuto
                processMulticastMessage(receivedMessage);

            } catch (Exception e) {
                if (running) {
                    System.err.println("[MulticastListener] Errore ricezione multicast: " + e.getMessage());
                    // Continua il loop - errori temporanei non fermano il listener
                }
            }
        }

        System.out.println("[MulticastListener] Thread listener terminato");
    }

    /**
     * Processa un messaggio multicast ricevuto dal server
     * Filtra e mostra solo notifiche pertinenti per questo utente
     *
     * @param jsonMessage messaggio JSON ricevuto via multicast
     */
    private void processMulticastMessage(String jsonMessage) {
        try {
            // Parsing JSON del messaggio multicast
            JsonObject notification = JsonParser.parseString(jsonMessage).getAsJsonObject();

            // Controllo tipo messaggio
            if (!notification.has("type") || !"priceThreshold".equals(notification.get("type").getAsString())) {
                return; // Ignora messaggi di tipo diverso
            }

            // Estrazione dati notifica
            String notificationUsername = notification.get("username").getAsString();
            int thresholdPrice = notification.get("thresholdPrice").getAsInt();
            int currentPrice = notification.get("currentPrice").getAsInt();
            String message = notification.get("message").getAsString();

            // Filtra solo notifiche per questo utente
            if (username.equals(notificationUsername)) {
                displayPriceNotification(thresholdPrice, currentPrice, message);
            }

        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore processing messaggio multicast: " + e.getMessage());
            System.err.println("[MulticastListener] Messaggio ricevuto: " + jsonMessage);
        }
    }

    /**
     * Mostra notifica di soglia prezzo all'utente
     * Formatta e presenta la notifica in modo user-friendly
     *
     * @param thresholdPrice soglia che è stata superata
     * @param currentPrice prezzo corrente BTC
     * @param message messaggio della notifica
     */
    private void displayPriceNotification(int thresholdPrice, int currentPrice, String message) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("🚨 NOTIFICA PREZZO BTC 🚨");
        System.out.println("=".repeat(60));
        System.out.println("Messaggio: " + message);
        System.out.println("Soglia registrata: " + formatPrice(thresholdPrice) + " USD");
        System.out.println("Prezzo corrente: " + formatPrice(currentPrice) + " USD");
        System.out.println("Utente: " + username);
        System.out.println("=".repeat(60));
        System.out.print(">> "); // Ripristina prompt per input utente
    }

    /**
     * Ferma il listener multicast in modo pulito
     * Abbandona il gruppo multicast e chiude le risorse
     */
    public void stop() {
        System.out.println("[MulticastListener] Fermando listener multicast...");

        running = false;

        try {
            if (multicastSocket != null) {
                // Abbandona il gruppo multicast (leaveGroup dalle slide)
                if (group != null) {
                    multicastSocket.leaveGroup(group);
                }

                // Chiude socket multicast
                multicastSocket.close();

                System.out.println("[MulticastListener] Listener multicast fermato");
            }
        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore durante stop listener: " + e.getMessage());
        }
    }

    // === UTILITY METHODS ===

    /**
     * Formatta prezzo in millesimi per display user-friendly
     *
     * @param priceInMillis prezzo in millesimi USD
     * @return prezzo formattato (es: "58.000")
     */
    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Verifica se il listener è attualmente in esecuzione
     *
     * @return true se il listener è attivo
     */
    public boolean isRunning() {
        return running;
    }
}

package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.*;

/**
 * Thread listener per ricevere notifiche multicast UDP dal server CROSS
 * Implementa il servizio di notifiche per soglie di prezzo BTC (solo vecchio ordinamento)
 */
public class MulticastListener implements Runnable {

    // Socket multicast per ricezione messaggi
    private MulticastSocket multicastSocket;

    // Gruppo multicast a cui unirsi
    private InetSocketAddress group;

    // Interfaccia di rete per multicast
    private NetworkInterface netInterface;

    // Indirizzo e porta del gruppo multicast
    private final String multicastAddress;
    private final int multicastPort;

    // Username dell'utente per logging
    private final String username;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

    /**
     * Costruttore del listener multicast
     */
    public MulticastListener(String multicastAddress, int multicastPort, String username) {
        this.multicastAddress = multicastAddress;
        this.multicastPort = multicastPort;
        this.username = username;
        this.running = false;
    }

    /**
     * Avvia il listener multicast
     */
    public void start() throws IOException {
        try {
            // Crea socket multicast
            multicastSocket = new MulticastSocket(multicastPort);

            // Configura gruppo multicast
            group = new InetSocketAddress(InetAddress.getByName(multicastAddress), multicastPort);

            // Ottiene interfaccia di rete (prova diverse opzioni per compatibilità)
            netInterface = getNetworkInterface();

            // Unisciti al gruppo multicast
            multicastSocket.joinGroup(group, netInterface);

            running = true;

            System.out.println("[MulticastListener] Listener avviato");
            System.out.println("[MulticastListener] Gruppo multicast: " + multicastAddress + ":" + multicastPort);
            System.out.println("[MulticastListener] Utente: " + username);

        } catch (IOException e) {
            System.err.println("[MulticastListener] Errore avvio listener multicast: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Ottiene l'interfaccia di rete appropriata per multicast
     */
    private NetworkInterface getNetworkInterface() throws SocketException {
        // Prova prima con l'interfaccia loopback per test locali
        try {
            NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
            if (loopback != null && loopback.supportsMulticast()) {
                return loopback;
            }
        } catch (Exception e) {
            // Ignora e prova altre opzioni
        }

        // Prova con l'interfaccia di default
        try {
            return NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
        } catch (Exception e) {
            // Ultima risorsa: prima interfaccia disponibile
            return NetworkInterface.getNetworkInterfaces().nextElement();
        }
    }

    /**
     * Loop principale di ascolto messaggi multicast
     */
    @Override
    public void run() {
        byte[] buffer = new byte[1024];

        System.out.println("[MulticastListener] Thread avviato");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Prepara pacchetto per ricezione
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Imposta timeout per permettere verifica del flag running
                multicastSocket.setSoTimeout(5000);

                // Riceve messaggio multicast
                multicastSocket.receive(packet);

                // Estrae messaggio
                String receivedMessage = new String(
                        packet.getData(), 0, packet.getLength(), "UTF-8"
                );

                // Processa notifica
                processMulticastNotification(receivedMessage);

            } catch (SocketTimeoutException e) {
                // Timeout normale - continua loop
                continue;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[MulticastListener] Errore ricezione multicast: " + e.getMessage());
                }
            }
        }

        System.out.println("[MulticastListener] Thread terminato");
    }

    /**
     * Processa notifica multicast ricevuta
     */
    private void processMulticastNotification(String jsonMessage) {
        try {
            // Parsing JSON
            JsonObject notification = JsonParser.parseString(jsonMessage).getAsJsonObject();

            if (!notification.has("type")) {
                System.err.println("[MulticastListener] Notifica senza campo 'type'");
                return;
            }

            String type = notification.get("type").getAsString();

            if ("priceThreshold".equals(type)) {
                displayPriceThresholdNotification(notification);
            } else {
                System.out.println("[MulticastListener] Tipo notifica sconosciuto: " + type);
            }

        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore processing notifica multicast: " + e.getMessage());
        }
    }

    /**
     * Visualizza notifica soglia prezzo superata
     */
    private void displayPriceThresholdNotification(JsonObject notification) {
        try {
            int thresholdPrice = notification.get("thresholdPrice").getAsInt();
            int currentPrice = notification.get("currentPrice").getAsInt();
            String message = notification.get("message").getAsString();

            // Crea separatore con metodo compatibile Java 8
            String separator = repeatString("=", 60);

            System.out.println("\n" + separator);
            System.out.println("NOTIFICA PREZZO BTC");
            System.out.println(separator);
            System.out.println("Messaggio: " + message);
            System.out.println("Soglia registrata: " + formatPrice(thresholdPrice) + " USD");
            System.out.println("Prezzo corrente: " + formatPrice(currentPrice) + " USD");
            System.out.println("Utente: " + username);
            System.out.println(separator);
            System.out.print(">> "); // Ripristina prompt per input utente

        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore visualizzazione notifica prezzo: " + e.getMessage());
        }
    }

    /**
     * Ferma il listener multicast in modo pulito
     */
    public void stop() {
        System.out.println("[MulticastListener] Fermando listener multicast...");

        running = false;

        try {
            if (multicastSocket != null) {
                // Abbandona il gruppo multicast
                if (group != null) {
                    multicastSocket.leaveGroup(group, netInterface);
                }

                // Chiude socket multicast
                multicastSocket.close();

                System.out.println("[MulticastListener] Listener multicast fermato");
            }
        } catch (Exception e) {
            System.err.println("[MulticastListener] Errore durante stop listener: " + e.getMessage());
        }
    }

    /**
     * Formatta prezzo in millesimi per display user-friendly
     */
    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Verifica se il listener è attualmente in esecuzione
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Metodo helper per ripetere una stringa n volte (alternativa a String.repeat() per Java 8)
     */
    private String repeatString(String str, int times) {
        StringBuilder sb = new StringBuilder(str.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}
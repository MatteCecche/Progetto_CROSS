package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Thread listener per ricevere notifiche UDP asincrone dal server CROSS
 * Gestisce le notifiche di trade eseguiti secondo formato ALLEGATO 1
 */
public class UDPNotificationListener implements Runnable {

    // Socket UDP per ricezione messaggi
    private DatagramSocket udpSocket;

    // Porta UDP su cui ascoltare
    private final int udpPort;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

    // Username dell'utente per logging
    private final String username;

    /**
     * Costruttore del listener UDP
     */
    public UDPNotificationListener(int udpPort, String username) {
        this.udpPort = udpPort;
        this.username = username;
        this.running = false;
    }

    /**
     * Avvia il listener UDP
     */
    public void start() throws Exception {
        try {
            // Crea socket UDP sulla porta specificata
            udpSocket = new DatagramSocket(udpPort);

            running = true;

            System.out.println("[UDPListener] Listener avviato sulla porta " + udpPort);
            System.out.println("[UDPListener] In ascolto per notifiche trade per utente: " + username);

        } catch (Exception e) {
            System.err.println("[UDPListener] Errore avvio listener UDP: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Loop principale di ascolto messaggi UDP
     */
    @Override
    public void run() {
        byte[] buffer = new byte[4096]; // Buffer per messaggi UDP

        System.out.println("[UDPListener] Thread listener avviato");

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Prepara pacchetto per ricezione
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // Riceve messaggio UDP (bloccante)
                udpSocket.receive(packet);

                // Estrae messaggio dal pacchetto
                String receivedMessage = new String(
                        packet.getData(), 0, packet.getLength(), "UTF-8"
                );

                // Processa il messaggio ricevuto
                processNotification(receivedMessage);

            } catch (Exception e) {
                if (running) {
                    System.err.println("[UDPListener] Errore ricezione UDP: " + e.getMessage());
                }
            }
        }

        System.out.println("[UDPListener] Thread listener terminato");
    }

    /**
     * Processa una notifica UDP ricevuta dal server
     */
    private void processNotification(String jsonMessage) {
        try {
            // Parsing JSON del messaggio
            JsonObject notification = JsonParser.parseString(jsonMessage).getAsJsonObject();

            // Verifica tipo notifica
            if (!notification.has("notification")) {
                System.err.println("[UDPListener] Messaggio senza campo 'notification'");
                return;
            }

            String notificationType = notification.get("notification").getAsString();

            // Gestisce notifica trade eseguiti
            if ("closedTrades".equals(notificationType)) {
                if (notification.has("trades")) {
                    JsonArray trades = notification.getAsJsonArray("trades");
                    displayTradeNotification(trades);
                }
            } else {
                System.err.println("[UDPListener] Tipo notifica sconosciuto: " + notificationType);
            }

        } catch (Exception e) {
            System.err.println("[UDPListener] Errore processing messaggio UDP: " + e.getMessage());
            System.err.println("[UDPListener] Messaggio ricevuto: " + jsonMessage);
        }
    }

    /**
     * Mostra notifica di trade eseguito all'utente
     */
    private void displayTradeNotification(JsonArray trades) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("⚡ NOTIFICA: TRADE ESEGUITO");
        System.out.println("=".repeat(70));

        for (int i = 0; i < trades.size(); i++) {
            JsonObject trade = trades.get(i).getAsJsonObject();

            // Estrai informazioni dal trade
            int orderId = trade.get("orderId").getAsInt();
            String type = trade.get("type").getAsString();
            String orderType = trade.get("orderType").getAsString();
            int size = trade.get("size").getAsInt();
            int price = trade.get("price").getAsInt();

            // Informazioni opzionali
            String counterparty = trade.has("counterparty") ?
                    trade.get("counterparty").getAsString() : "N/A";
            long timestamp = trade.has("timestamp") ?
                    trade.get("timestamp").getAsLong() : System.currentTimeMillis() / 1000;

            // Formattazione output
            System.out.println("\n📋 Dettagli Trade:");
            System.out.println("   Order ID:       " + orderId);
            System.out.println("   Tipo:           " + (type.equals("bid") ? "ACQUISTO (BID)" : "VENDITA (ASK)"));
            System.out.println("   Order Type:     " + orderType.toUpperCase());
            System.out.println("   Quantità:       " + formatSize(size) + " BTC");
            System.out.println("   Prezzo:         " + formatPrice(price) + " USD");
            System.out.println("   Controparte:    " + counterparty);
            System.out.println("   Timestamp:      " + timestamp);

            if (i < trades.size() - 1) {
                System.out.println("\n" + "-".repeat(70));
            }
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.print(">> "); // Ripristina prompt per input utente
        System.out.flush();
    }

    /**
     * Ferma il listener UDP in modo pulito
     */
    public void stop() {
        System.out.println("[UDPListener] Fermando listener UDP...");

        running = false;

        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
                System.out.println("[UDPListener] Socket UDP chiuso");
            }
        } catch (Exception e) {
            System.err.println("[UDPListener] Errore durante stop listener: " + e.getMessage());
        }
    }

    /**
     * Formatta prezzo in millesimi per display user-friendly
     */
    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    /**
     * Formatta size in millesimi per display user-friendly
     */
    private String formatSize(int sizeInMillis) {
        return String.format("%.3f", sizeInMillis / 1000.0);
    }

    /**
     * Verifica se il listener è attualmente in esecuzione
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Ottiene la porta UDP su cui il listener è in ascolto
     */
    public int getUdpPort() {
        return udpPort;
    }
}

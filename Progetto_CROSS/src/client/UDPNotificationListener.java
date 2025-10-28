package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

/*
 * Listener per notifiche UDP dal server
 */
public class UDPNotificationListener implements Runnable {

    // stringhe per separatori messaggi
    private static final String SEPARATOR_EQUALS = "================================================================================";
    private static final String SEPARATOR_DASHES = "--------------------------------------------------------------------------------";

    // Socket UDP per ricezione messaggi
    private DatagramSocket udpSocket;

    // Porta UDP richiesta
    private final int requestedPort;

    // Porta UDP effettiva
    private int actualPort;

    // Flag per controllo esecuzione thread
    private volatile boolean running;

    // Username dell'utente per logging
    private final String username;

    public UDPNotificationListener(int udpPort, String username) {
        this.requestedPort = udpPort;
        this.username = username;
        this.running = false;
        this.actualPort = -1;
    }

    public void start() throws Exception {
        try {
            udpSocket = new DatagramSocket(requestedPort);
            actualPort = udpSocket.getLocalPort();
            running = true;
        } catch (Exception e) {
            System.err.println("Errore avvio: " + e.getMessage());
            throw e;
        }
    }

    public int getActualPort() {
        return actualPort;
    }

    public void run() {
        byte[] buffer = new byte[4096];

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                processNotification(message);

            } catch (Exception e) {
                if (running) {
                    System.err.println("Errore ricezione: " + e.getMessage());
                }
            }
        }
    }

    private void processNotification(String jsonMessage) {
        try {
            JsonObject notification = JsonParser.parseString(jsonMessage).getAsJsonObject();

            if (!notification.has("notification")) {
                return;
            }

            String type = notification.get("notification").getAsString();

            if ("closedTrades".equals(type) && notification.has("trades")) {
                JsonArray trades = notification.getAsJsonArray("trades");
                displayTrades(trades);
            }

        } catch (Exception e) {
            System.err.println("Errore processing: " + e.getMessage());
        }
    }

    private void displayTrades(JsonArray trades) {
        System.out.println("\n" + SEPARATOR_EQUALS);
        System.out.println("                          TRADE ESEGUITI");
        System.out.println(SEPARATOR_EQUALS);
        System.out.printf("%-10s %-12s %-15s %-20s %-20s%n",
                "Order ID", "Tipo", "Quantità", "Prezzo", "Controparte");
        System.out.println(SEPARATOR_DASHES);

        for (int i = 0; i < trades.size(); i++) {
            JsonObject trade = trades.get(i).getAsJsonObject();
            int orderId = trade.get("orderId").getAsInt();
            String type = trade.get("type").getAsString();
            String tipoStr = "bid".equals(type) ? "ACQUISTO" : "VENDITA";
            int size = trade.get("size").getAsInt();
            int price = trade.get("price").getAsInt();
            String counterparty = trade.has("counterparty") ?
                    trade.get("counterparty").getAsString() : "N/A";

            System.out.printf("%-10d %-12s %-15s %-20s %-20s%n",
                    orderId, tipoStr,
                    formatSize(size) + " BTC",
                    formatPrice(price) + " USD",
                    counterparty);
        }

        System.out.println(SEPARATOR_EQUALS);
        System.out.print(">> ");
        System.out.flush();
    }

    public void stop() {
        running = false;
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        } catch (Exception e) {
            System.err.println("Errore stop: " + e.getMessage());
        }
    }

    private String formatPrice(int priceInMillis) {

        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    private String formatSize(int sizeInMillis) {

        return String.format("%.3f", sizeInMillis / 1000.0);
    }
}
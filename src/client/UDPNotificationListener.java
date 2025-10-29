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

    // Colori per risposte
    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_BOLD = "\u001B[1m";

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
            System.err.println(ANSI_RED + "Errore avvio: " + e.getMessage() + ANSI_RESET);
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
                    System.err.println(ANSI_RED + "Errore ricezione: " + e.getMessage() + ANSI_RESET);
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
            System.err.println(ANSI_RED + "Errore processing: " + e.getMessage() + ANSI_RESET);
        }
    }

    private void displayTrades(JsonArray trades) {
        System.out.println(ANSI_YELLOW + SEPARATOR_EQUALS + ANSI_RESET);
        System.out.println(ANSI_RED + "                                TRADE ESEGUITI" + ANSI_RESET);
        System.out.println(ANSI_YELLOW +SEPARATOR_EQUALS + ANSI_RESET);
        System.out.printf(ANSI_RED +"%-10s %-12s %-15s %-20s %-20s%n", "Order ID", "Tipo", "Quantità", "Prezzo", "Controparte" + ANSI_RESET);
        System.out.println(ANSI_YELLOW +SEPARATOR_DASHES + ANSI_RESET);

        for (int i = 0; i < trades.size(); i++) {
            JsonObject trade = trades.get(i).getAsJsonObject();
            int orderId = trade.get("orderId").getAsInt();
            String type = trade.get("type").getAsString();
            String tipoStr = "bid".equals(type) ? "ACQUISTO" : "VENDITA";
            int size = trade.get("size").getAsInt();
            int price = trade.get("price").getAsInt();
            String counterparty = trade.has("counterparty") ? trade.get("counterparty").getAsString() : "N/A";

            System.out.printf(ANSI_GREEN + "%-10d %-12s %-15s %-20s %-20s%n", orderId, tipoStr, formatSize(size) + " BTC", formatPrice(price) + " USD", counterparty + ANSI_RESET);
        }

        System.out.println(ANSI_YELLOW +SEPARATOR_EQUALS + ANSI_RESET);
        System.out.print(ANSI_BOLD + ">> " + ANSI_RESET);
        System.out.flush();
    }

    public void stop() {
        running = false;
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Errore stop: " + e.getMessage() + ANSI_RESET);
        }
    }

    private String formatPrice(int priceInMillis) {

        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    private String formatSize(int sizeInMillis) {

        return String.format("%.3f", sizeInMillis / 1000.0);
    }
}
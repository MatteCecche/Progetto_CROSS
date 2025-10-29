package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import server.utility.Colors;

public class UDPNotificationListener implements Runnable {

    private static final String SEPARATOR_EQUALS = "================================================================================";
    private static final String SEPARATOR_DASHES = "--------------------------------------------------------------------------------";
    private DatagramSocket udpSocket;
    private final int requestedPort;
    private int actualPort;
    private volatile boolean running;
    private final String username;
    private final Object consoleLock;

    public UDPNotificationListener(int udpPort, String username, Object consoleLock) {
        this.requestedPort = udpPort;
        this.username = username;
        this.running = false;
        this.actualPort = -1;
        this.consoleLock = consoleLock;
    }

    public void start() throws Exception {
        try {
            udpSocket = new DatagramSocket(requestedPort);
            actualPort = udpSocket.getLocalPort();
            running = true;
        } catch (Exception e) {
            System.err.println(Colors.RED + "Errore avvio: " + e.getMessage() + Colors.RESET);
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
                    System.err.println(Colors.RED + "Errore ricezione: " + e.getMessage() + Colors.RESET);
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
            System.err.println(Colors.RED + "Errore processing: " + e.getMessage() + Colors.RESET);
        }
    }

    private void displayTrades(JsonArray trades) {
        synchronized (consoleLock) {
            System.out.print("\r");
            System.out.println(Colors.YELLOW + SEPARATOR_EQUALS + Colors.RESET);
            System.out.println(Colors.RED + "                                TRADE ESEGUITI" + Colors.RESET);
            System.out.println(Colors.YELLOW + SEPARATOR_EQUALS + Colors.RESET);
            System.out.printf(Colors.RED + "%-10s %-12s %-15s %-20s %-20s%n", "Order ID", "Tipo", "Quantità", "Prezzo", "Controparte" + Colors.RESET);
            System.out.println(Colors.YELLOW + SEPARATOR_DASHES + Colors.RESET);

            for (int i = 0; i < trades.size(); i++) {
                JsonObject trade = trades.get(i).getAsJsonObject();
                int orderId = trade.get("orderId").getAsInt();
                String type = trade.get("type").getAsString();
                String tipoStr = "bid".equals(type) ? "ACQUISTO" : "VENDITA";
                int size = trade.get("size").getAsInt();
                int price = trade.get("price").getAsInt();
                String counterparty = trade.has("counterparty") ? trade.get("counterparty").getAsString() : "N/A";

                System.out.printf(Colors.GREEN + "%-10d %-12s %-15s %-20s %-20s%n", orderId, tipoStr, formatSize(size) + " BTC", formatPrice(price) + " USD", counterparty + Colors.RESET);
            }

            System.out.println(Colors.YELLOW + SEPARATOR_EQUALS + Colors.RESET);
            System.out.print(Colors.BOLD + ">> " + Colors.RESET);
            System.out.flush();
        }
    }

    public void stop() {
        running = false;
        try {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        } catch (Exception e) {
            System.err.println(Colors.RED + "Errore stop: " + e.getMessage() + Colors.RESET);
        }
    }

    private String formatPrice(int priceInMillis) {

        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    private String formatSize(int sizeInMillis) {

        return String.format("%.3f", sizeInMillis / 1000.0);
    }
}
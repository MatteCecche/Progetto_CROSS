package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/*
 * Listener per notifiche UDP dal server
 */
public class UDPNotificationListener implements Runnable {

    // Socket UDP per ricezione messaggi
    private DatagramSocket udpSocket;

    // Porta UDP richiesta (0 = porta automatica)
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

            if (requestedPort == 0) {
                System.out.println("[UDPListener] Porta automatica assegnata: " + actualPort);
            } else {
                System.out.println("[UDPListener] In ascolto sulla porta " + actualPort);
            }

        } catch (Exception e) {
            System.err.println("[UDPListener] Errore avvio: " + e.getMessage());
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

                String message = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                processNotification(message);

            } catch (Exception e) {
                if (running) {
                    System.err.println("[UDPListener] Errore ricezione: " + e.getMessage());
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
            System.err.println("[UDPListener] Errore processing: " + e.getMessage());
        }
    }

    private void displayTrades(JsonArray trades) {
        System.out.println("\n" + repeat("=", 60));
        System.out.println("TRADE ESEGUITO");
        System.out.println(repeat("=", 60));

        for (int i = 0; i < trades.size(); i++) {
            JsonObject trade = trades.get(i).getAsJsonObject();

            int orderId = trade.get("orderId").getAsInt();
            String type = trade.get("type").getAsString();
            int size = trade.get("size").getAsInt();
            int price = trade.get("price").getAsInt();

            System.out.println("\nOrder ID: " + orderId);
            System.out.println("Tipo: " + (type.equals("bid") ? "ACQUISTO" : "VENDITA"));
            System.out.println("Quantita: " + formatSize(size) + " BTC");
            System.out.println("Prezzo: " + formatPrice(price) + " USD");

            if (trade.has("counterparty")) {
                System.out.println("Controparte: " + trade.get("counterparty").getAsString());
            }
        }

        System.out.println("\n" + repeat("=", 60));
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
            System.err.println("[UDPListener] Errore stop: " + e.getMessage());
        }
    }

    private String formatPrice(int priceInMillis) {
        return String.format("%,.0f", priceInMillis / 1000.0);
    }

    private String formatSize(int sizeInMillis) {
        return String.format("%.3f", sizeInMillis / 1000.0);
    }

    private String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}